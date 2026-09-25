package com.meguri.core.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Minimal, bounded OOXML text codec; it never interprets macros or executes package content. */
final class DocxDocumentCodec {
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_DOCUMENT_XML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRY_BYTES = 4 * 1024 * 1024;

    private DocxDocumentCodec() { }

    static String extractText(Path path) {
        Document document = parse(readDocumentXml(path));
        StringBuilder result = new StringBuilder();
        NodeList paragraphs = document.getElementsByTagNameNS(WORD_NS, "p");
        for (int index = 0; index < paragraphs.getLength(); index++) {
            if (index > 0) result.append('\n');
            NodeList parts = ((Element) paragraphs.item(index)).getElementsByTagNameNS(WORD_NS, "t");
            for (int part = 0; part < parts.getLength(); part++) {
                result.append(parts.item(part).getTextContent());
            }
        }
        return result.toString();
    }

    static byte[] replaceText(Path path, List<DocumentEditPlan.Replacement> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new DocumentEditingException("A Word document edit needs at least one exact replacement.");
        }
        Document document = parse(readDocumentXml(path));
        for (DocumentEditPlan.Replacement operation : operations) {
            int matches = replaceExactlyOnce(document, operation);
            if (matches != 1) {
                throw new DocumentEditingException(
                        "A Word replacement must match exactly one unchanged text run: " + operation.find());
            }
        }
        return writePackage(path, serialize(document));
    }

    private static int replaceExactlyOnce(Document document, DocumentEditPlan.Replacement operation) {
        int matches = 0;
        NodeList texts = document.getElementsByTagNameNS(WORD_NS, "t");
        for (int index = 0; index < texts.getLength(); index++) {
            Node node = texts.item(index);
            String current = node.getTextContent();
            int first = current.indexOf(operation.find());
            if (first < 0) continue;
            if (current.indexOf(operation.find(), first + operation.find().length()) >= 0) {
                matches += 2;
                continue;
            }
            node.setTextContent(current.substring(0, first) + operation.replace()
                    + current.substring(first + operation.find().length()));
            matches++;
        }
        return matches;
    }

    private static byte[] readDocumentXml(Path path) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(DOCUMENT_XML);
            if (entry == null || entry.isDirectory()) {
                throw new DocumentEditingException("The DOCX file has no readable document body.");
            }
            if (entry.getSize() > MAX_DOCUMENT_XML_BYTES) {
                throw new DocumentEditingException("The DOCX document body exceeds the supported size limit.");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return readBounded(input, MAX_DOCUMENT_XML_BYTES);
            }
        } catch (IOException error) {
            throw new DocumentEditingException("The selected DOCX file could not be read.", error);
        }
    }

    private static byte[] writePackage(Path source, byte[] documentXml) {
        try (ZipFile zip = new ZipFile(source.toFile()); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (ZipOutputStream output = new ZipOutputStream(bytes)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copied = new ZipEntry(entry.getName());
                    copied.setTime(entry.getTime());
                    output.putNextEntry(copied);
                    if (!entry.isDirectory()) {
                        if (DOCUMENT_XML.equals(entry.getName())) {
                            output.write(documentXml);
                        } else {
                            try (InputStream input = zip.getInputStream(entry)) {
                                input.transferTo(output);
                            }
                        }
                    }
                    output.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new DocumentEditingException("The DOCX file could not be prepared for editing.", error);
        }
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception error) {
            throw new DocumentEditingException("The DOCX document body is not safe XML.", error);
        }
    }

    private static byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception error) {
            throw new DocumentEditingException("The DOCX edit could not be encoded safely.", error);
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw new DocumentEditingException("The DOCX document body exceeds the supported size limit.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
