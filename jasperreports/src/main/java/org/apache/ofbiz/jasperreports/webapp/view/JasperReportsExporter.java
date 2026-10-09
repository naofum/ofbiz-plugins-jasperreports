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

import java.io.OutputStream;
import java.util.Locale;

import org.apache.ofbiz.base.util.UtilValidate;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.JRXmlExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import net.sf.jasperreports.export.SimpleXmlExporterOutput;

/**
 * Maps a response content type to the matching JasperReports exporter and
 * writes a filled {@link JasperPrint} document to an output stream.
 *
 * <p>PDF (the default) uses {@link JasperExportManager#exportReportToPdfStream}
 * which is backed by OpenPDF, the same PDF library OFBiz ships, avoiding any
 * version conflict. Text-based formats (CSV, XML) use the dedicated
 * JasperReports 7.x exporters with a {@code Writer} output.</p>
 */
final class JasperReportsExporter {

    private JasperReportsExporter() {
    }

    private static final String CONTENT_TYPE_PDF = "application/pdf";
    private static final String CONTENT_TYPE_CSV = "text/csv";
    private static final String CONTENT_TYPE_XML = "text/xml";
    private static final String CONTENT_TYPE_XML_APP = "application/xml";
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * Exports the given document in the format indicated by {@code contentType}.
     * Unknown content types fall back to PDF.
     *
     * @param jasperPrint the filled report document
     * @param contentType the desired output MIME type
     * @param encoding    the character encoding for text formats (defaults to UTF-8)
     * @param out         the stream to write the exported document to
     * @throws JRException if the export fails
     */
    static void export(JasperPrint jasperPrint, String contentType, String encoding, OutputStream out)
            throws JRException {

        String type = UtilValidate.isNotEmpty(contentType)
                ? contentType.toLowerCase(Locale.ROOT) : CONTENT_TYPE_PDF;
        String enc = UtilValidate.isNotEmpty(encoding) ? encoding : DEFAULT_ENCODING;

        switch (type) {
        case CONTENT_TYPE_CSV:
            exportCsv(jasperPrint, enc, out);
            break;
        case CONTENT_TYPE_XML:
        case CONTENT_TYPE_XML_APP:
            exportXml(jasperPrint, enc, out);
            break;
        case CONTENT_TYPE_PDF:
        default:
            JasperExportManager.exportReportToPdfStream(jasperPrint, out);
            break;
        }
    }

    private static void exportCsv(JasperPrint jasperPrint, String encoding, OutputStream out) throws JRException {
        JRCsvExporter exporter = new JRCsvExporter();
        exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
        SimpleWriterExporterOutput output = new SimpleWriterExporterOutput(out, encoding);
        exporter.setExporterOutput(output);
        exporter.exportReport();
    }

    private static void exportXml(JasperPrint jasperPrint, String encoding, OutputStream out) throws JRException {
        JRXmlExporter exporter = new JRXmlExporter();
        exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
        // SimpleXmlExporterOutput has no setEncoding(); encoding is a constructor argument.
        SimpleXmlExporterOutput output = new SimpleXmlExporterOutput(out, encoding);
        exporter.setExporterOutput(output);
        exporter.exportReport();
    }
}
