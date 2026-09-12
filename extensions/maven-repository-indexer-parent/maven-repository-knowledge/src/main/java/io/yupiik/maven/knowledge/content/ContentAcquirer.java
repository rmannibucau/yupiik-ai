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
package io.yupiik.maven.knowledge.content;

import io.yupiik.htmltomarkdown.HtmlLightParser;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acquires documentation content for an artifact following the locked fallback chain:
 * <ol>
 *     <li>{@code -sources.jar} - extracted source text (preferred).</li>
 *     <li>{@code -javadoc.jar} - extracted Javadoc/HTML text.</li>
 *     <li>pom {@code <url>} - bounded webfetch, skipped when the URL points at a git/source-hosting repo.</li>
 *     <li>bytecode - the class listing is handed to the LLM (strategy B).</li>
 *     <li>{@code NONE}.</li>
 * </ol>
 */
public class ContentAcquirer {

    private final LocalRepository repository;
    private final WebFetch webFetch;
    private final KnowledgeConfig config;
    private final DocumentBuilderFactory documentBuilderFactory;
    private final HtmlLightParser htmlParser;
    private static final Set<String> GIT_HOSTS = Set.of("github.com", "gitlab.com", "bitbucket.org", "codeberg.org");

    public ContentAcquirer(final LocalRepository repository, final WebFetch webFetch, final KnowledgeConfig config) {
        this.repository = repository;
        this.webFetch = webFetch;
        this.config = config;
        this.documentBuilderFactory = secureDocumentBuilderFactory();
        this.htmlParser = new HtmlLightParser();
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        final var factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (final javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return factory;
    }

    public ContentAcquired acquire(final io.yupiik.maven.knowledge.Gav gav) {
        final var artifact = repository.resolve(gav);
        if (!artifact.present()) {
            return ContentAcquired.none(gav);
        }
        final var sources = artifact.sourcesJar();
        if (sources != null && Files.isRegularFile(sources)) {
            return ContentAcquired.of(gav, ContentKind.SOURCES, fromSourcesJar(sources, gav));
        }
        final var javadoc = artifact.javadocJar();
        if (javadoc != null && Files.isRegularFile(javadoc)) {
            return ContentAcquired.of(gav, ContentKind.JAVADOC, fromJavadocJar(javadoc));
        }
        final var pom = artifact.pom();
        if (pom != null) {
            final var doc = readDoc(pom, gav);
            if (doc != null) {
                return ContentAcquired.of(gav, ContentKind.DOC, doc);
            }
        }
        final var classes = artifact.mainJar();
        if (classes != null && Files.isRegularFile(classes)) {
            return ContentAcquired.of(gav, ContentKind.CLASSES, classListing(classes));
        }
        return ContentAcquired.none(gav);
    }

    private String readDoc(final Path pom, final io.yupiik.maven.knowledge.Gav gav) {
        try {
            final var doc = documentBuilderFactory.newDocumentBuilder().parse(pom.toFile());
            final var url = firstText(doc, "url");
            if (url == null || url.isBlank() || !isDocHost(url) || config.boundedFetch() == null) {
                return null;
            }
            try {
                return webFetch.fetch(
                        url,
                        config.boundedFetch().timeoutMs(),
                        config.boundedFetch().maxBytes());
            } catch (final IOException e) {
                return null;
            }
        } catch (final Exception e) {
            return null;
        }
    }

    private static boolean isDocHost(final String url) {
        try {
            final var host = java.net.URI.create(url).getHost();
            return host != null && GIT_HOSTS.stream().noneMatch(host::contains);
        } catch (final Exception e) {
            return false;
        }
    }

    private static String firstText(final org.w3c.dom.Document doc, final String tag) {
        final var list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    private String fromSourcesJar(final Path jar, final io.yupiik.maven.knowledge.Gav gav) {
        final var sb = new StringBuilder();
        sb.append("Artifact: ").append(gav.coordinate()).append('\n');
        try (final var zip = new ZipFile(jar.toFile())) {
            final var entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".java"))
                    .limit(400)
                    .toList();
            for (final var entry : entries) {
                appendEntry(sb, zip, entry, 40_000);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    private String fromJavadocJar(final Path jar) {
        final var sb = new StringBuilder();
        try (final var zip = new ZipFile(jar.toFile())) {
            final var docEntries = new ArrayList<ZipEntry>();
            final var index = zip.getEntry("index-all.html");
            if (index != null) {
                docEntries.add(index);
            }
            final var all = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith("-summary.html"))
                    .limit(200)
                    .toList();
            docEntries.addAll(all);
            for (final var entry : docEntries) {
                try (final var in = zip.getInputStream(entry)) {
                    sb.append("## ").append(entry.getName()).append('\n');
                    sb.append(htmlToText(in)).append('\n');
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    private String classListing(final Path jar) {
        final var classes = new LinkedHashSet<String>();
        try (final var zip = new ZipFile(jar.toFile())) {
            final var entries = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .map(ZipEntry::getName)
                    .filter(n -> !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.'))
                    .sorted()
                    .toList();
            classes.addAll(entries);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return "Artifact classes:\n" + String.join("\n", classes);
    }

    private static void appendEntry(final StringBuilder sb, final ZipFile zip, final ZipEntry entry, final int cap) {
        sb.append("### ").append(entry.getName()).append('\n');
        try (final var in = zip.getInputStream(entry)) {
            final var chunk = new String(in.readNBytes(cap), java.nio.charset.StandardCharsets.UTF_8);
            sb.append(chunk).append('\n');
        } catch (final IOException e) {
            sb.append("<unreadable>\n");
        }
    }

    private String htmlToText(final InputStream in) throws IOException {
        final var raw = new String(in.readNBytes(300_000), java.nio.charset.StandardCharsets.UTF_8);
        final var text = htmlParser.toMarkdown(raw);
        return text.substring(0, Math.min(2000, text.length()));
    }
}
