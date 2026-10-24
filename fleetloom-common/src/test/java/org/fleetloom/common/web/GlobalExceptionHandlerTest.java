package org.fleetloom.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.error.CommonErrorCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bizExceptionMapsToItsHttpStatusAndCode() throws Exception {
        mvc.perform(get("/biz"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("already exists"))
                .andExpect(jsonPath("$.data.field").value("code"));
    }

    @Test
    void validationErrorReturnsFieldMessages() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.data.name").exists());
    }

    @Test
    void malformedBodyIsBadRequest() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void invalidParametersAreBadRequests() throws Exception {
        mvc.perform(get("/codes/bad code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.data.code").exists());
        mvc.perform(get("/count").param("n", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.n").value("invalid value many"));
        mvc.perform(get("/count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.n").value("required"));
    }

    @Test
    void constraintViolationsFromValidatedBeansAreBadRequests() throws Exception {
        MockMvc withViolations = MockMvcBuilders.standaloneSetup(new ViolatingController())
                .setControllerAdvice(new ConstraintViolationHandler(), new GlobalExceptionHandler()).build();
        withViolations.perform(get("/violated"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.name").exists());
    }

    @Test
    void wrongMethodKeepsItsStatus() throws Exception {
        mvc.perform(post("/count"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("internal error"));
    }

    record NamedRequest(@NotBlank String name) {
    }

    @RestController
    static class ViolatingController {

        @GetMapping("/violated")
        void violated() {
            var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            throw new jakarta.validation.ConstraintViolationException(validator.validate(new NamedRequest("")));
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/biz")
        void biz() {
            throw new BizException(CommonErrorCode.CONFLICT, "already exists", java.util.Map.of("field", "code"));
        }

        @PostMapping("/validated")
        void validated(@Valid @RequestBody NamedRequest request) {
        }

        @GetMapping("/codes/{code}")
        void code(@org.springframework.web.bind.annotation.PathVariable
                @jakarta.validation.constraints.Pattern(regexp = "[A-Z]+") String code) {
        }

        @GetMapping("/count")
        void count(@org.springframework.web.bind.annotation.RequestParam int n) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
