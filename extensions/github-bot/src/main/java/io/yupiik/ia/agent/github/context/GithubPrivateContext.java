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
package io.yupiik.ia.agent.github.context;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Host-only export, not a Git clone or a credential proxy. Call after GithubContextSnapshot.stage,
 * before exposing the attempt to any executor. The caller must exclusively own the attempt during staging.
 * The client and its credentials remain owned by the caller; no shell or Git process is started.
 */
@ApplicationScoped
public class GithubPrivateContext {
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    private static final int MAX_FILES = 2_000;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{40}");
    private final GitHubClient github;
    private final JsonMapper json;

    public GithubPrivateContext(final GitHubClient github, final JsonMapper json) {
        this.github = github;
        this.json = json;
    }

    /** Exports the entire bounded head tree or fails without publishing a partial source snapshot. */
    public SourceSnapshot stage(final TaskDocument task, final Path attempt) throws IOException {
        final var repo = task.repository();
        if (!(task.kind() == TaskKind.review)
                || task.repository() == null
                || !REPOSITORY.matcher(repo).matches()
                || repo.endsWith("/.")
                || repo.endsWith("/..")) {
            throw new IllegalArgumentException("Expected a review task with a valid repository");
        }
        final long number = integer(task.number());
        if (number <= 0) {
            throw new IllegalArgumentException("Expected a positive pull request number");
        }
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        checkWorkspace(workspace);
        final var context = workspace.resolve("github-context.json");
        if (!Files.isRegularFile(context, LinkOption.NOFOLLOW_LINKS) || Files.size(context) > 4 * 1024 * 1024) {
            throw new IOException("Expected a bounded, non-symbolic host context snapshot");
        }
        final var snapshot = json.fromString(GithubContextSnapshot.Snapshot.class, Files.readString(context));
        if (snapshot.schemaVersion() != 1 || !repo.equals(snapshot.repo()) || snapshot.number() != number) {
            throw new IllegalArgumentException("Source task does not match host context");
        }
        final var head = sha(snapshot.pull().head().sha());
        return export(attempt, repo, number, head);
    }

    /** Pins the upstream default branch before exposing a credential-free solve attempt. */
    public SolveContext stageSolve(final TaskDocument task, final Path attempt) throws IOException {
        final var repo = task.repository();
        if (!(task.kind() == TaskKind.solve)
                || task.repository() == null
                || !REPOSITORY.matcher(repo).matches()
                || repo.endsWith("/.")
                || repo.endsWith("/..")) {
            throw new IllegalArgumentException("Expected a solve task with a valid repository");
        }
        final long number = integer(task.number());
        if (number <= 0) {
            throw new IllegalArgumentException("Expected a positive issue number");
        }
        final var repository = object(github.get("/repos/" + repo));
        if (!(repository.get("default_branch") instanceof String branch)
                || branch.isBlank()
                || branch.length() > 255
                || branch.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Expected a valid upstream default branch");
        }
        final var commit = object(github.get("/repos/" + repo + "/commits/"
                + URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("+", "%20")));
        final var head = sha(commit.get("sha"));
        if (((task.strictSolveBaseSha() != null) && !head.equals(task.strictSolveBaseSha()))
                || ((task.strictSolveBaseBranch() != null) && !branch.equals(task.strictSolveBaseBranch()))) {
            throw new IllegalStateException("Pinned strict solve base changed; operator reconciliation required");
        }
        final var result = new SolveContext(repo, number, branch, head);
        final var destination = attempt.resolve("workspace/github-solve.json");
        checkWorkspace(destination.toAbsolutePath().normalize().getParent());
        vacant(destination);
        export(attempt, repo, number, head);
        try {
            Files.writeString(
                    destination, json.toString(result), StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException | RuntimeException failure) {
            // No executor exists yet; these paths were exclusively created by this staging call.
            AttemptPreparation.delete(attempt.resolve("workspace/repository"));
            Files.deleteIfExists(attempt.resolve("workspace/github-source.json"));
            throw failure;
        }
        return result;
    }

    private SourceSnapshot export(final Path attempt, final String repo, final long number, final String head)
            throws IOException {
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        checkWorkspace(workspace);
        final var target = workspace.resolve("repository");
        final var manifest = workspace.resolve("github-source.json");
        vacant(target);
        vacant(manifest);
        final var budget = new Budget();
        final var prefix = "/repos/" + repo + "/git/";
        final var commit = object(budget.read(prefix + "commits/" + head));
        if (!head.equals(sha(commit.get("sha")))) {
            throw new IllegalStateException("GitHub returned a different commit");
        }
        final var treeSha = sha(object(commit.get("tree")).get("sha"));
        final var tree = object(budget.read(prefix + "trees/" + treeSha + "?recursive=1"));
        if (!treeSha.equals(sha(tree.get("sha")))
                || !Boolean.FALSE.equals(tree.get("truncated"))
                || !(tree.get("tree") instanceof List<?> entries)
                || entries.size() > MAX_ENTRIES) {
            throw new IllegalStateException("Incomplete or oversized GitHub source tree");
        }
        final var names = new HashSet<String>();
        final var blobs = new ArrayList<Blob>();
        long bytes = 0;
        for (final var value : entries) {
            final var entry = object(value);
            final var path = safePath(entry.get("path"));
            if (!names.add(path.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Duplicate GitHub source path");
            }
            sha(entry.get("sha"));
            if ("tree".equals(entry.get("type")) && "040000".equals(entry.get("mode"))) {
                continue;
            }
            if (!"blob".equals(entry.get("type"))
                    || !("100644".equals(entry.get("mode")) || "100755".equals(entry.get("mode")))) {
                throw new IllegalStateException("GitHub source symlinks, gitlinks and special files are refused");
            }
            final long size = integer(entry.get("size"));
            bytes += size;
            if (size < 0 || size > MAX_FILE_BYTES || bytes > MAX_SOURCE_BYTES || blobs.size() >= MAX_FILES) {
                throw new IllegalStateException("GitHub source exceeds file or byte limit");
            }
            blobs.add(new Blob(path, sha(entry.get("sha")), size));
        }
        final var content = new LinkedHashMap<String, byte[]>();
        final var decodedBlobs = new LinkedHashMap<String, byte[]>();
        for (final var blob : blobs) {
            final var cached = decodedBlobs.get(blob.sha());
            if (cached != null) {
                if (cached.length != blob.size()) {
                    throw new IllegalStateException("Inconsistent GitHub source blob size");
                }
                content.put(blob.path(), cached);
                continue;
            }
            final var response = object(budget.read(prefix + "blobs/" + blob.sha()));
            if (!blob.sha().equals(sha(response.get("sha")))
                    || integer(response.get("size")) != blob.size()
                    || !"base64".equals(response.get("encoding"))
                    || !(response.get("content") instanceof String encoded)
                    || encoded.length() > 2 * MAX_FILE_BYTES) {
                throw new IllegalStateException("Invalid GitHub source blob");
            }
            // GitHub wraps base64 in LF; do not use MIME decoding, which silently ignores arbitrary junk.
            final var decoded =
                    Base64.getDecoder().decode(encoded.replace("\n", "").replace("\r", ""));
            if (decoded.length != blob.size() || !blob.sha().equals(blobSha(decoded))) {
                throw new IllegalStateException("GitHub source blob integrity failure");
            }
            content.put(blob.path(), decoded);
            decodedBlobs.put(blob.sha(), decoded);
        }
        final var result = new SourceSnapshot(1, repo, number, head, treeSha, List.copyOf(content.keySet()), bytes);
        final var metadata = json.toString(result);
        checkWorkspace(workspace);
        vacant(target);
        vacant(manifest);
        final var temporary = Files.createTempDirectory(workspace, ".source-");
        boolean published = false;
        boolean manifestCreated = false;
        try {
            for (final var entry : content.entrySet()) {
                final var file = temporary.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            try (final var output =
                    Files.newOutputStream(manifest, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
                manifestCreated = true;
                output.write(metadata.getBytes(StandardCharsets.UTF_8));
            }
            return result;
        } catch (final IOException | RuntimeException failure) {
            try {
                if (published) {
                    AttemptPreparation.delete(target);
                }
                if (manifestCreated) {
                    Files.deleteIfExists(manifest);
                }
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            AttemptPreparation.delete(temporary);
        }
    }

    private static String safePath(final Object value) {
        if (!(value instanceof String path)
                || path.length() > 1024
                || path.startsWith("/")
                || path.contains("\\")
                || path.contains(":")
                || path.codePoints().anyMatch(c -> Character.isISOControl(c))) {
            throw new IllegalStateException("Unsafe GitHub source path");
        }
        final var parts = path.split("/", -1);
        if (parts.length > 32) {
            throw new IllegalStateException("GitHub source path is too deep");
        }
        for (final var part : parts) {
            final var lower = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty()
                    || part.length() > 255
                    || part.equals(".")
                    || part.equals("..")
                    || lower.equals(".git")
                    || part.endsWith(".")
                    || part.endsWith(" ")
                    || WINDOWS_RESERVED_NAME.matcher(lower).matches()) {
                throw new IllegalStateException("Unsafe GitHub source path segment");
            }
        }
        return path;
    }

    private static Map<?, ?> object(final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected GitHub source object");
        }
        return map;
    }

    private static long integer(final Object value) {
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString()).longValueExact();
            } catch (final NumberFormatException | ArithmeticException invalid) {
                throw new IllegalArgumentException("Expected integral GitHub source number", invalid);
            }
        }
        throw new IllegalArgumentException("Expected integral GitHub source number");
    }

    private static String sha(final Object value) {
        if (!(value instanceof String text) || !SHA.matcher(text).matches()) {
            throw new IllegalStateException("Expected immutable GitHub SHA-1");
        }
        return text;
    }

    private static String blobSha(final byte[] bytes) {
        try {
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void checkWorkspace(final Path workspace) throws IOException {
        Path current = workspace.getRoot();
        for (final var part : workspace) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Source staging requires existing non-symbolic attempt directories");
            }
        }
    }

    private static void vacant(final Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GitHub source destination already exists");
        }
    }

    private record Blob(String path, String sha, long size) {}

    @JsonModel
    public record SolveContext(
            @Property(documentation = "Host-validated upstream repository.")
            String repo,

            @Property(documentation = "Issue being solved.") long number,

            @Property(documentation = "Pinned upstream default branch.")
            String baseBranch,

            @Property(documentation = "Pinned upstream commit for the proposed changes.")
            String baseSha) {}

    @JsonModel
    public record SourceSnapshot(
            @Property(documentation = "Source snapshot schema version.")
            int schemaVersion,

            @Property(documentation = "Fixed upstream repository used for every authenticated request.")
            String repo,

            @Property(documentation = "Pull request number from the host context.")
            long number,

            @Property(documentation = "Reviewed immutable head commit, shared with github-context.json.")
            String headSha,

            @Property(documentation = "Root Git tree SHA at the reviewed head.")
            String treeSha,

            @Property(documentation = "Complete exported regular-file paths relative to repository/.")
            List<String> files,

            @Property(documentation = "Total decoded source bytes.")
            long sourceBytes) {}

    @JsonModel
    public record SourceResponse(
            @Property(documentation = "Dynamic response measured against the cumulative encoded response budget.")
            Object payload) {}

    private final class Budget {
        private long bytes;

        private Object read(final String endpoint) {
            final var page = github.page(endpoint);
            bytes += json.toString(new SourceResponse(page.body())).getBytes(StandardCharsets.UTF_8).length;
            if (page.next() != null || bytes > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException("GitHub source exceeds response budget or has unexpected pagination");
            }
            return page.body();
        }
    }
}
