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
package io.yupiik.llm.client.codex.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * The cached Codex OAuth session, largely mirroring the shape of the Codex CLI
 * {@code ~/.codex/auth.json} {@code tokens} object so the values can be interchanged (the values
 * are never read back from the CLI file though: each app persists its own copy under its storage
 * home). The access token is short lived (about an hour); the refresh token may rotate on every
 * refresh and must be persisted each time.
 *
 * @param accessToken the short-lived ChatGPT account access token.
 * @param refreshToken the longer-lived refresh token (rotated on refresh).
 * @param accountId the {@code chatgpt_account_id} extracted from the id_token claim.
 * @param expiresAt epoch millis when the access token expires.
 * @param idToken the raw id token (bearer of the account id claim); kept for re-deriving the
 *     account id without a round trip.
 */
@JsonModel
public record CodexTokenStore(
        String accessToken, String refreshToken, String accountId, Long expiresAt, String idToken) {

    /** True when the access token is absent or already expired (with a small clock-skew guard). */
    public boolean expired(final long now) {
        return accessToken == null || accessToken.isBlank() || expiresAt == null || expiresAt <= now + 60_000;
    }

    /** True when a refresh token is present, ie. this session can be refreshed without re-login. */
    public boolean refreshable() {
        return refreshToken != null && !refreshToken.isBlank();
    }
}
