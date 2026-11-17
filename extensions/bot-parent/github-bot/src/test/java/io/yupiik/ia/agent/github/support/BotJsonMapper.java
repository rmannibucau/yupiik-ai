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
package io.yupiik.ia.agent.github.support;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.internal.codec.EnumJsonCodec;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Profile$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileEnvironment$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileFilesystem$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileGroups$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileLinux$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileMeta$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileSecurity$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileWorkdir$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Session$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Sessions$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.model.KubeconfigDocument$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.model.KubeconfigDocument$User$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.AuthTokenProvider$Installation$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.AuthTokenProvider$InstallationToken$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.AuthTokenProvider$JwtClaims$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.AuthTokenProvider$JwtHeader$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Author$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$CommentInput$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Connection$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Data$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Envelope$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Node$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$PageInfo$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Publication$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Repository$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.DiscussionClient$Variables$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$BlobRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$CommentRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$CommitRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$ForkRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$GraphqlRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$GraphqlStatus$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$PullRequestRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$RefRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$ReviewFinding$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$ReviewRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$TreeEntry$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.GitHubClient$TreeRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$Connection$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$Data$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$Envelope$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$Node$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$PageInfo$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.MentionSearchClient$Repository$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Blob$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Commit$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Comparison$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$ContextEntry$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$FeedItem$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$FeedUser$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$File$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Head$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Issue$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Membership$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$ObjectId$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Permission$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Permissions$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Pull$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$PullLink$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Ref$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Repository$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Review$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$SourceEntry$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$SourceTree$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$Tree$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$TreeEntry$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.RestResponses$User$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$Author$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$Data$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$Envelope$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$PageInfo$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$PullConnection$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$PullNode$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$Repository$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$ReviewConnection$FusionJsonCodec;
import io.yupiik.ia.agent.github.client.ReviewClient$ReviewNode$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.ContextHttpServer$RpcFailure$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.ContextHttpServer$RpcResponse$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextBroker$Request$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextBroker$Response$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$ChangedFile$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Comment$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$ContextPull$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$ContextRef$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Conversation$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Pull$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Ref$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Review$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot$Snapshot$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubPrivateContext$SolveContext$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.GithubPrivateContext$SourceSnapshot$FusionJsonCodec;
import io.yupiik.ia.agent.github.context.agent.ContextClient$RpcResponse$FusionJsonCodec;
import io.yupiik.ia.agent.github.execution.AttemptPreparation$UserRequest$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$Assessment$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$Change$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$Finding$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$Review$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.AgentResult$StrictSolve$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$FailureDetail$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$ForkIntent$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$PublicationObject$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$Run$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$SolvePublication$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskDocument$TaskList$FusionJsonCodec;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.service.FixPublisher$UpdateRefsData$FusionJsonCodec;
import io.yupiik.ia.agent.github.service.FixPublisher$UpdateRefsEnvelope$FusionJsonCodec;
import io.yupiik.ia.agent.github.service.FixPublisher$UpdateRefsResult$FusionJsonCodec;
import io.yupiik.ia.agent.github.store.StateDocument$FusionJsonCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Registers domain codecs for tests that deliberately do not start the Fusion container. */
public final class BotJsonMapper {
    private BotJsonMapper() {}

    public static JsonMapperImpl create(
            final Collection<JsonCodec<?>> codecs, final Function<String, Optional<String>> configuration) {
        final var all = new ArrayList<JsonCodec<?>>(codecs);
        all.add(new ContextClient$RpcResponse$FusionJsonCodec());
        all.add(new ContextHttpServer$RpcResponse$FusionJsonCodec());
        all.add(new ContextHttpServer$RpcFailure$FusionJsonCodec());
        all.add(new ReviewClient$Envelope$FusionJsonCodec());
        all.add(new ReviewClient$Data$FusionJsonCodec());
        all.add(new ReviewClient$Repository$FusionJsonCodec());
        all.add(new ReviewClient$PullConnection$FusionJsonCodec());
        all.add(new ReviewClient$ReviewConnection$FusionJsonCodec());
        all.add(new ReviewClient$PullNode$FusionJsonCodec());
        all.add(new ReviewClient$ReviewNode$FusionJsonCodec());
        all.add(new ReviewClient$Author$FusionJsonCodec());
        all.add(new ReviewClient$PageInfo$FusionJsonCodec());
        all.add(new AuthTokenProvider$Installation$FusionJsonCodec());
        all.add(new AuthTokenProvider$InstallationToken$FusionJsonCodec());
        all.add(new RestResponses$FeedItem$FusionJsonCodec());
        all.add(new RestResponses$FeedUser$FusionJsonCodec());
        all.add(new RestResponses$Blob$FusionJsonCodec());
        all.add(new RestResponses$SourceTree$FusionJsonCodec());
        all.add(new RestResponses$SourceEntry$FusionJsonCodec());
        all.add(new RestResponses$ContextEntry$FusionJsonCodec());
        all.add(new GithubContextSnapshot$ContextPull$FusionJsonCodec());
        all.add(new GithubContextSnapshot$ContextRef$FusionJsonCodec());
        all.add(new FixPublisher$UpdateRefsEnvelope$FusionJsonCodec());
        all.add(new FixPublisher$UpdateRefsData$FusionJsonCodec());
        all.add(new FixPublisher$UpdateRefsResult$FusionJsonCodec());

        all.add(new RestResponses$Repository$FusionJsonCodec());
        all.add(new RestResponses$Permissions$FusionJsonCodec());
        all.add(new RestResponses$Commit$FusionJsonCodec());
        all.add(new RestResponses$ObjectId$FusionJsonCodec());
        all.add(new RestResponses$Ref$FusionJsonCodec());
        all.add(new RestResponses$Pull$FusionJsonCodec());
        all.add(new RestResponses$Head$FusionJsonCodec());
        all.add(new RestResponses$User$FusionJsonCodec());
        all.add(new RestResponses$Permission$FusionJsonCodec());
        all.add(new RestResponses$Membership$FusionJsonCodec());
        all.add(new RestResponses$Comparison$FusionJsonCodec());
        all.add(new RestResponses$Tree$FusionJsonCodec());
        all.add(new RestResponses$TreeEntry$FusionJsonCodec());
        all.add(new RestResponses$Review$FusionJsonCodec());
        all.add(new RestResponses$File$FusionJsonCodec());
        all.add(new RestResponses$Issue$FusionJsonCodec());
        all.add(new RestResponses$PullLink$FusionJsonCodec());
        all.add(new AgentConfigurationDocument$FusionJsonCodec());
        all.add(new DiscussionClient$Variables$FusionJsonCodec());
        all.add(new DiscussionClient$CommentInput$FusionJsonCodec());
        all.add(new DiscussionClient$Envelope$FusionJsonCodec());
        all.add(new DiscussionClient$Data$FusionJsonCodec());
        all.add(new DiscussionClient$Repository$FusionJsonCodec());
        all.add(new DiscussionClient$Publication$FusionJsonCodec());
        all.add(new DiscussionClient$Connection$FusionJsonCodec());
        all.add(new DiscussionClient$PageInfo$FusionJsonCodec());
        all.add(new DiscussionClient$Author$FusionJsonCodec());
        all.add(new DiscussionClient$Node$FusionJsonCodec());
        all.add(new AgentResult$FusionJsonCodec());
        all.add(new AgentResult$Assessment$FusionJsonCodec());
        all.add(new AgentResult$Review$FusionJsonCodec());
        all.add(new AgentResult$Finding$FusionJsonCodec());
        all.add(new AgentResult$StrictSolve$FusionJsonCodec());
        all.add(new AgentResult$Change$FusionJsonCodec());

        all.add(new AttemptPreparation$UserRequest$FusionJsonCodec());
        all.add(new GitHubClient$CommentRequest$FusionJsonCodec());
        all.add(new GitHubClient$GraphqlRequest$FusionJsonCodec());
        all.add(new GitHubClient$GraphqlStatus$FusionJsonCodec());
        all.add(new MentionSearchClient$Envelope$FusionJsonCodec());
        all.add(new MentionSearchClient$Data$FusionJsonCodec());
        all.add(new MentionSearchClient$Connection$FusionJsonCodec());
        all.add(new MentionSearchClient$Node$FusionJsonCodec());
        all.add(new MentionSearchClient$Repository$FusionJsonCodec());
        all.add(new MentionSearchClient$PageInfo$FusionJsonCodec());
        all.add(new GitHubClient$ForkRequest$FusionJsonCodec());
        all.add(new GitHubClient$ReviewRequest$FusionJsonCodec());
        all.add(new GitHubClient$ReviewFinding$FusionJsonCodec());
        all.add(new GitHubClient$BlobRequest$FusionJsonCodec());
        all.add(new GitHubClient$TreeRequest$FusionJsonCodec());
        all.add(new GitHubClient$TreeEntry$FusionJsonCodec());
        all.add(new GitHubClient$CommitRequest$FusionJsonCodec());
        all.add(new GitHubClient$RefRequest$FusionJsonCodec());
        all.add(new GitHubClient$PullRequestRequest$FusionJsonCodec());
        all.add(new AuthTokenProvider$InstallationTokenRequest$FusionJsonCodec());
        all.add(new AuthTokenProvider$JwtHeader$FusionJsonCodec());
        all.add(new AuthTokenProvider$JwtClaims$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Profile$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileMeta$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileGroups$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileWorkdir$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileFilesystem$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileSecurity$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileLinux$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileEnvironment$FusionJsonCodec());

        all.add(new NonoExecutionBackend$Sessions$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Session$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Snapshot$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Pull$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Ref$FusionJsonCodec());
        all.add(new GithubContextSnapshot$ChangedFile$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Review$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Comment$FusionJsonCodec());
        all.add(new GithubContextSnapshot$Conversation$FusionJsonCodec());
        all.add(new GithubPrivateContext$SourceSnapshot$FusionJsonCodec());
        all.add(new GithubPrivateContext$SolveContext$FusionJsonCodec());
        all.add(new GithubContextBroker$Request$FusionJsonCodec());
        all.add(new GithubContextBroker$Response$FusionJsonCodec());
        all.add(new KubeconfigDocument$FusionJsonCodec());
        all.add(new KubeconfigDocument$User$FusionJsonCodec());
        all.add(new TaskDocument$FusionJsonCodec());
        all.add(new TaskDocument$FailureDetail$FusionJsonCodec());
        all.add(new TaskDocument$Run$FusionJsonCodec());
        all.add(new TaskDocument$ForkIntent$FusionJsonCodec());
        all.add(new TaskDocument$SolvePublication$FusionJsonCodec());
        all.add(new TaskDocument$PublicationObject$FusionJsonCodec());
        all.add(new EnumJsonCodec<>(TaskKind.class, List.of(TaskKind.values()), TaskKind::name));
        all.add(new EnumJsonCodec<>(TaskState.class, List.of(TaskState.values()), TaskState::name));
        all.add(new TaskDocument$TaskList$FusionJsonCodec());
        all.add(new StateDocument$FusionJsonCodec());
        return new JsonMapperImpl(all, configuration::apply);
    }
}
