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

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record PollOptions(
        @Property(
                defaultValue = "\"now\"",
                documentation = "Initial scan lower bound: now on first scan, or an ISO-8601 instant for backfill.")
        String since,

        @Property(
                defaultValue = "20",
                documentation = "Maximum new pages per endpoint per poll, plus one overlap page when resuming."
                        + " Incomplete scans never advance watermarks.")
        int pageBudget,

        @Property(
                defaultValue = "false",
                documentation = "Poll native pull request review bodies for mentions, including submitted review"
                        + " summaries.")
        boolean pollReviews,

        @Property(
                defaultValue = "false",
                documentation =
                        "Poll repository Discussions, comments and replies for help or solve mentions. Requires Discussions read/write permissions for publication.")
        boolean pollDiscussions) {}
