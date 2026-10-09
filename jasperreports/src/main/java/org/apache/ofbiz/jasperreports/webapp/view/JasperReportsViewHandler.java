/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.jasperreports.webapp.view;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.location.FlexibleLocation;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.datasource.GenericHelperInfo;
import org.apache.ofbiz.entity.transaction.TransactionFactoryLoader;
import org.apache.ofbiz.webapp.control.ConfigXMLReader;
import org.apache.ofbiz.webapp.view.AbstractViewHandler;
import org.apache.ofbiz.webapp.view.ViewHandlerException;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;

/**
 * JasperReportsViewHandler - a generic OFBiz view handler that renders
 * JasperReports templates (.jrxml / compiled .jasper) to PDF (and other
 * formats) streaming the result straight into the servlet response.
 *
 * <p>The report template is identified by the view-map {@code page} attribute,
 * either as an OFBiz {@code component://} location or as an absolute file path.
 * A {@code .jrxml} source is compiled on the fly; a {@code .jasper} file is
 * loaded directly.</p>
 *
 * <p>Report data is filled using a JDBC {@link Connection} obtained from the
 * OFBiz entity engine (default entity group) so report SQL queries run against
 * the same database OFBiz uses. All request parameters are forwarded to the
 * report as fill parameters.</p>
 *
 * <p>Alternatively, an upstream controller event may prepare the data through
 * the OFBiz entity engine and place a {@link JRDataSource} in the
 * {@link #ATTR_DATA_SOURCE} request attribute (and, optionally, a parameter
 * {@code Map} in {@link #ATTR_PARAMETERS}). When a data source is present the
 * report is filled from it instead of a JDBC connection, so the JRXML needs no
 * database-specific SQL and stays decoupled from physical table/column names.</p>
 *
 * <p>The output content type is taken from the view-map {@code content-type}
 * attribute (default {@code application/pdf}) and may be overridden per request
 * with the {@code jrContentType} parameter. Supported formats: PDF, HTML, CSV,
 * XML. An optional {@code jrOutputFileName} parameter sets the download
 * filename via the {@code Content-Disposition} header.</p>
 */
public class JasperReportsViewHandler extends AbstractViewHandler {

    private static final String MODULE = JasperReportsViewHandler.class.getName();
    private static final String RES_ERROR = "JasperReportsErrorUiLabels";

    /** Request parameter to override the view-map content type. */
    private static final String PARAM_CONTENT_TYPE = "jrContentType";
    /** Request parameter to override the report template location. */
    private static final String PARAM_TEMPLATE = "jrTemplate";
    /** Request parameter to set the download file name. */
    private static final String PARAM_OUTPUT_FILE_NAME = "jrOutputFileName";

    /**
     * Request attribute (not parameter) that may hold a pre-built
     * {@link JRDataSource}. When present, the report is filled from this data
     * source instead of a JDBC connection. Set by an upstream controller event
     * that fetches data through the OFBiz entity engine (so the JRXML needs no
     * database-specific SQL).
     */
    public static final String ATTR_DATA_SOURCE = "jrDataSource";
    /**
     * Request attribute (not parameter) that may hold a {@code Map<String,Object>}
     * of report parameters. Merged over the request-parameter map, so entity
     * values prepared server-side take precedence over raw request parameters.
     */
    public static final String ATTR_PARAMETERS = "jrParameters";

    private static final String CONTENT_TYPE_PDF = "application/pdf";

    @Override
    public void init(ServletContext context) throws ViewHandlerException {
        // Nothing to initialize: JasperReports managers are stateless/static.
    }

    @Override
    public Map<String, Object> prepareViewContext(HttpServletRequest request, HttpServletResponse response,
            ConfigXMLReader.ViewMap viewMap) {
        return Map.of();
    }

    @Override
    public void render(String name, String page, String info, String contentType, String encoding,
            HttpServletRequest request, HttpServletResponse response, Map<String, Object> context)
            throws ViewHandlerException {

        if (request == null) {
            throw new ViewHandlerException("Null HttpServletRequest object");
        }

        Locale locale = UtilHttp.getLocale(request);

        // Resolve the report template location: request parameter wins over the
        // view-map page so a single generic view-map can serve many reports.
        String template = request.getParameter(PARAM_TEMPLATE);
        if (UtilValidate.isEmpty(template)) {
            template = page;
        }
        if (UtilValidate.isEmpty(template)) {
            throw new ViewHandlerException(
                    UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorNoTemplate", locale));
        }

        // Resolve content type: request parameter overrides view-map, which
        // overrides the PDF default.
        String overrideContentType = request.getParameter(PARAM_CONTENT_TYPE);
        if (UtilValidate.isNotEmpty(overrideContentType)) {
            contentType = overrideContentType;
        }
        if (UtilValidate.isEmpty(contentType)) {
            contentType = CONTENT_TYPE_PDF;
        }

        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (delegator == null) {
            throw new ViewHandlerException(
                    UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorNoDelegator", locale));
        }

        JasperReport jasperReport = loadReport(template, locale);
        Map<String, Object> parameters = buildReportParameters(request);

        // A pre-built JRDataSource supplied by an upstream event lets the report
        // be filled from entity-engine data (no DB-specific SQL in the JRXML).
        JRDataSource dataSource = null;
        Object dsAttr = request.getAttribute(ATTR_DATA_SOURCE);
        if (dsAttr instanceof JRDataSource) {
            dataSource = (JRDataSource) dsAttr;
        }

        Connection connection = null;
        try {
            JasperPrint jasperPrint;
            if (dataSource != null) {
                // Fill from the injected data source; no JDBC connection needed.
                jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, dataSource);
            } else {
                // Fall back to filling via a JDBC connection from the entity
                // engine, so JRXML <queryString> SQL runs against OFBiz's DB.
                connection = getConnection(delegator);
                jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, connection);
            }
            if (jasperPrint.getPages() == null || jasperPrint.getPages().isEmpty()) {
                Debug.logWarning("JasperReports produced an empty document for template: " + template, MODULE);
            }

            // Optional download filename.
            String outputFileName = request.getParameter(PARAM_OUTPUT_FILE_NAME);
            if (UtilValidate.isNotEmpty(outputFileName)) {
                outputFileName = UtilHttp.canonicalizeParameter(outputFileName);
                response.setHeader("Content-Disposition", "attachment; filename=\"" + outputFileName + "\"");
            }

            response.setContentType(contentType);
            try (OutputStream out = response.getOutputStream()) {
                JasperReportsExporter.export(jasperPrint, contentType, encoding, out);
                out.flush();
            }
        } catch (JRException e) {
            String errMsg = UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorFillOrExport", locale)
                    + ": " + e.toString();
            Debug.logError(e, errMsg, MODULE);
            throw new ViewHandlerException(errMsg, e);
        } catch (IOException e) {
            throw new ViewHandlerException("Error writing to the response output stream: " + e.toString(), e);
        } catch (SQLException e) {
            throw new ViewHandlerException("Error obtaining a JDBC connection: " + e.toString(), e);
        } catch (GenericEntityException e) {
            throw new ViewHandlerException("Entity engine error: " + e.toString(), e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    Debug.logWarning(e, "Could not close JDBC connection after report rendering", MODULE);
                }
            }
        }
    }

    /**
     * Loads (and compiles if necessary) the report template.
     * @param template an OFBiz {@code component://} location or file path
     * @param locale   the request locale, used for error messages
     * @return a ready-to-fill {@link JasperReport}
     * @throws ViewHandlerException if the template cannot be found or compiled
     */
    private JasperReport loadReport(String template, Locale locale) throws ViewHandlerException {
        try {
            URL templateUrl = FlexibleLocation.resolveLocation(template);
            if (templateUrl == null) {
                throw new ViewHandlerException(
                        UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorTemplateNotFound", locale)
                                + ": " + template);
            }
            String lower = template.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jrxml")) {
                try (InputStream in = templateUrl.openStream()) {
                    return JasperCompileManager.compileReport(in);
                }
            }
            // Treat anything else (notably .jasper) as a pre-compiled report.
            try (InputStream in = templateUrl.openStream()) {
                return (JasperReport) JRLoader.loadObject(in);
            }
        } catch (JRException e) {
            // JRXmlLoader aggregates parse errors; surface the full chain so the
            // real cause (e.g. SAXParseException) is visible in the log.
            StringBuilder detail = new StringBuilder(e.toString());
            Throwable cause = e.getCause();
            while (cause != null) {
                detail.append(" | caused by: ").append(cause.toString());
                cause = cause.getCause();
            }
            String errMsg = UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorCompile", locale)
                    + " [" + template + "]: " + detail;
            Debug.logError(e, errMsg, MODULE);
            throw new ViewHandlerException(errMsg, e);
        } catch (IOException e) {
            String errMsg = UtilProperties.getMessage(RES_ERROR, "JasperReportsErrorTemplateNotFound", locale)
                    + " [" + template + "]: " + e.toString();
            Debug.logError(e, errMsg, MODULE);
            throw new ViewHandlerException(errMsg, e);
        }
    }

    /**
     * Builds the JasperReports fill parameters. Request parameters are passed
     * through first (so report-defined parameters resolve by name), then any
     * server-prepared parameter map held in the {@link #ATTR_PARAMETERS} request
     * attribute is merged on top (taking precedence, and allowing non-String
     * values such as dates and numbers).
     */
    private Map<String, Object> buildReportParameters(HttpServletRequest request) {
        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> requestParams = UtilHttp.getParameterMap(request);
        if (requestParams != null) {
            parameters.putAll(requestParams);
        }
        Object attr = request.getAttribute(ATTR_PARAMETERS);
        if (attr instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prepared = (Map<String, Object>) attr;
            parameters.putAll(prepared);
        }
        return parameters;
    }

    /**
     * Obtains a JDBC connection from the OFBiz entity engine for the default
     * entity group so report queries run against the OFBiz datasource.
     */
    private Connection getConnection(Delegator delegator) throws SQLException, GenericEntityException {
        GenericHelperInfo helperInfo = delegator.getGroupHelperInfo(
                delegator.getEntityGroupName("SequenceValueItem"));
        return TransactionFactoryLoader.getInstance().getConnection(helperInfo);
    }
}
