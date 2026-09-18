package com.eap.eap_matchengine.configuration.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchRecoverySourceControllerTest {

    @Test
    void sourceTokenShouldFailClosed() throws Exception {
        MatchRecoveryCaseService service = mock(MatchRecoveryCaseService.class);
        MatchRecoverySourceController controller = new MatchRecoverySourceController(service, "source-secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.list(10)).thenReturn(List.of());

        mvc.perform(get("/internal/recovery/v1/cases").param("limit", "10"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/recovery/v1/cases")
                        .param("limit", "10")
                        .header(MatchRecoverySourceController.TOKEN_HEADER, "source-secret"))
                .andExpect(status().isOk());
    }
}
