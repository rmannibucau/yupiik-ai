/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;

/**
 * Detects the format of raw tool outputs (cards and Ctrl+O overlay) and pretty-prints
 * the formats that can be reformatted safely. Everything is best effort: any parse or
 * rendering failure falls back to the raw text, never breaking the previous rendering.
 */
@ApplicationScoped
public class ToolOutputFormat {
    public enum Format {
        MARKDOWN,
        JSON,
        XML,
        YAML,
        DIFF,
        TEXT
    }

    private static final int MAX_PRETTY_LENGTH = 65536;
    private static final Pattern YAML_KEY =
            Pattern.compile("^[ \\t]*[A-Za-z_][A-Za-z0-9_. -]*:(\\s|$)", Pattern.MULTILINE);
    private static final Pattern MD_ATX = Pattern.compile("^#{1,6} ", Pattern.MULTILINE);
    private static final Pattern MD_LIST = Pattern.compile("^[-*+] ", Pattern.MULTILINE);
    private static final Pattern MD_FENCE = Pattern.compile("^```|^~~~", Pattern.MULTILINE);
    private static final Pattern MD_TABLE = Pattern.compile("^\\s*\\|.*\\|", Pattern.MULTILINE);

    /** Singleton bean: the caches are instance state, shared by every render tick. */
    private final Map<String, Format> detectCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Format> eldest) {
            return size() > 128;
        }
    };

    private final Map<String, String> prettyCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
            return size() > 16;
        }
    };
    private final PrettyJsonMapper pretty;

    public ToolOutputFormat(final JsonMapper mapper) {
        this.pretty = new PrettyJsonMapper(mapper);
    }

    /** Default construction for tests and manual wiring; the mapper must be closed by the owner. */
    public static ToolOutputFormat of(final JsonMapper mapper) {
        return new ToolOutputFormat(mapper);
    }

    /**
     * Classifies a raw tool output. Never throws: any probe failure downgrades to TEXT.
     * Results are cached (tool cards re-render on every tick).
     */
    public Format detect(final String output) {
        if (output == null || output.isBlank()) {
            return Format.TEXT;
        }
        synchronized (detectCache) {
            final var cached = detectCache.get(output);
            if (cached != null) {
                return cached;
            }
        }
        final var format = detectUncached(output);
        synchronized (detectCache) {
            detectCache.put(output, format);
        }
        return format;
    }

    private Format detectUncached(final String output) {
        try {
            if (DiffLines.looksLikeDiff(output)) {
                return Format.DIFF;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        final var trimmed = output.strip();
        try {
            if ((trimmed.startsWith("{") || trimmed.startsWith("["))
                    && trimmed.length() <= MAX_PRETTY_LENGTH
                    && pretty.fromString(Object.class, trimmed) != null) {
                return Format.JSON;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        try {
            if (trimmed.startsWith("<")
                    && !trimmed.contains("<!")
                    && trimmed.length() <= MAX_PRETTY_LENGTH
                    && parseXmlSecurely(trimmed) != null) {
                return Format.XML;
            }
        } catch (final Exception e) {
            // fall through to the next probe
        }
        try {
            if (looksLikeYaml(output)) {
                return Format.YAML;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        try {
            if (looksLikeMarkdown(output)) {
                return Format.MARKDOWN;
            }
        } catch (final RuntimeException e) {
            // fall through
        }
        return Format.TEXT;
    }

    private static boolean looksLikeYaml(final String output) {
        final var trimmed = output.strip();
        if (trimmed.startsWith("---") && trimmed.length() > 3 && trimmed.charAt(3) != ':') {
            return true;
        }
        var keys = 0;
        final var matcher = YAML_KEY.matcher(output);
        while (matcher.find() && keys < 2) {
            keys++;
        }
        return keys >= 2;
    }

    private static boolean looksLikeMarkdown(final String output) {
        var signals = 0;
        if (MD_ATX.matcher(output).find()) {
            signals++;
        }
        if (MD_LIST.matcher(output).find()) {
            signals++;
        }
        if (MD_FENCE.matcher(output).find()) {
            signals++;
        }
        if (MD_TABLE.matcher(output).find()) {
            signals++;
        }
        return signals >= 2;
    }

    /**
     * Re-indents a JSON tool output (2-space), or returns the input unchanged when it is
     * not parseable JSON or too large to reformat comfortably.
     */
    public String prettyJson(final String raw) {
        return pretty(raw, this::doPrettyJson);
    }

    /** Re-indents an XML tool output through the secured JDK identity transformer. */
    public String prettyXml(final String raw) {
        return pretty(raw, this::doPrettyXml);
    }

    private String pretty(final String raw, final java.util.function.UnaryOperator<String> printer) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_PRETTY_LENGTH) {
            return raw;
        }
        synchronized (prettyCache) {
            final var cached = prettyCache.get(raw);
            if (cached != null) {
                return cached;
            }
        }
        String result;
        try {
            result = printer.apply(raw);
        } catch (final Throwable e) {
            result = raw;
        }
        if (result == null) {
            result = raw;
        }
        synchronized (prettyCache) {
            prettyCache.put(raw, result);
        }
        return result;
    }

    private String doPrettyJson(final String raw) {
        final var trimmed = raw.strip();
        final var parsed = pretty.fromString(Object.class, trimmed);
        return parsed == null ? raw : pretty.toString(parsed);
    }

    private String doPrettyXml(final String raw) {
        try {
            final var trimmed = raw.strip();
            if (!trimmed.startsWith("<") || trimmed.contains("<!")) {
                return raw;
            }
            final var factory = DocumentBuilderFactory.newInstance();
            harden(factory);
            final var builder = factory.newDocumentBuilder();
            final Document document;
            try (var reader = new StringReader(trimmed)) {
                document = builder.parse(new org.xml.sax.InputSource(reader));
            }
            final var transformerFactory = TransformerFactory.newInstance();
            harden(transformerFactory);
            final var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            final var out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            final var prettyXml = out.toString();
            return prettyXml.isBlank() ? raw : prettyXml;
        } catch (final Exception e) {
            return raw;
        }
    }

    /** Returns the parsed XML document when the input is well-formed XML, else null (never throws). */
    private static org.w3c.dom.Document parseXmlSecurely(final String raw) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            harden(factory);
            try (var reader = new StringReader(raw)) {
                return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(reader));
            }
        } catch (final Exception e) {
            return null;
        }
    }

    /** Hardens an XML factory against XXE/external entity resolution (no remote lookups, ever). */
    private static void harden(final DocumentBuilderFactory factory) {
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (final javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    }

    private static void harden(final TransformerFactory factory) {
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (final javax.xml.transform.TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    }
}
