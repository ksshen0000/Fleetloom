package org.rcs3000.resource.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.rcs3000.resource.IntegrationTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class AuthIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1";

    @Autowired
    private MockMvc mvc;

    @Value("${rcs.security.jwt-secret}")
    private String secret;

    @Test
    void initialAdminLogsInAndGetsASignedToken() throws Exception {
        String body = mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"admin\", \"password\": \"admin12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(body).path("data").path("token").asString();

        // the gateway verifies tokens with the same shared secret
        Jwt jwt = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo("admin");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"admin\", \"password\": \"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    void usersAreManagedAndPasswordsNeverReturned() throws Exception {
        mvc.perform(post(API + "/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"op1\", \"displayName\": \"Operator\", \"password\": \"operator-1\", \"role\": \"OPERATOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mvc.perform(get(API + "/auth/me").header("X-User-Name", "op1"))
                .andExpect(jsonPath("$.data.role").value("OPERATOR"));
        mvc.perform(put(API + "/auth/me/password").header("X-User-Name", "op1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"wrong-one\", \"newPassword\": \"operator-2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        mvc.perform(put(API + "/users/op1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Operator\", \"role\": \"VIEWER\", \"enabled\": true}"))
                .andExpect(jsonPath("$.data.role").value("VIEWER"));
        mvc.perform(post(API + "/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"op1\", \"password\": \"operator-1\"}"))
                .andExpect(jsonPath("$.data.user.role").value("VIEWER"));
    }
}
