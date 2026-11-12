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
package io.yupiik.ia.agent.github;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.ia.agent.github.store.StateDocument$FusionJsonCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/** Registers domain codecs for tests that deliberately do not start the Fusion container. */
public final class BotJsonMapper {
    private BotJsonMapper() {}

    public static JsonMapperImpl create(
            final Collection<JsonCodec<?>> codecs, final Function<String, Optional<String>> configuration) {
        final var all = new ArrayList<JsonCodec<?>>(codecs);
        all.add(new AgentConfigurationDocument$FusionJsonCodec());
        all.add(new AgentResult$FusionJsonCodec());
        all.add(new AttemptPreparation$UserRequest$FusionJsonCodec());
        all.add(new GitHubClient$CommentRequest$FusionJsonCodec());
        all.add(new GitHubClient$GraphqlRequest$FusionJsonCodec());
        all.add(new GitHubClient$ForkRequest$FusionJsonCodec());
        all.add(new GitHubClient$ReviewRequest$FusionJsonCodec());
        all.add(new GitHubClient$ReviewFinding$FusionJsonCodec());
        all.add(new GitHubClient$BlobRequest$FusionJsonCodec());
        all.add(new GitHubClient$TreeRequest$FusionJsonCodec());
        all.add(new GitHubClient$TreeEntry$FusionJsonCodec());
        all.add(new GitHubClient$CommitRequest$FusionJsonCodec());
        all.add(new GitHubClient$RefRequest$FusionJsonCodec());
        all.add(new GitHubClient$PullRequestRequest$FusionJsonCodec());
        all.add(new GitHubClient$GitHubValue$FusionJsonCodec());
        all.add(new GitHubClient$GitHubMember$FusionJsonCodec());
        all.add(new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec());
        all.add(new AuthTokenProvider$JwtHeader$FusionJsonCodec());
        all.add(new AuthTokenProvider$JwtClaims$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Profile$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Sessions$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Snapshot$FusionJsonCodec());
        all.add(new GithubContextSnapshot$ContextResponse$FusionJsonCodec());
        all.add(new GithubPrivateContext$SourceSnapshot$FusionJsonCodec());
        all.add(new GithubPrivateContext$SourceResponse$FusionJsonCodec());
        all.add(new GithubPrivateContext$SolveContext$FusionJsonCodec());
        all.add(new GithubContextBroker$Request$FusionJsonCodec());
        all.add(new GithubContextBroker$Response$FusionJsonCodec());
        all.add(new KubeconfigDocument$FusionJsonCodec());
        all.add(new KubeconfigDocument$User$FusionJsonCodec());
        all.add(new TaskDocument$FusionJsonCodec());
        all.add(new TaskDocument$TaskList$FusionJsonCodec());
        all.add(new StateDocument$FusionJsonCodec());
        return new JsonMapperImpl(all, configuration::apply);
    }
}
