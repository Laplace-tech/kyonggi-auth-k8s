package com.kyonggi.backend.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.kyonggi.backend.infra.AbstractIntegrationTest;

/**
 * Actuator Health (운영/쿠버네티스)
 * - 스프링부트가 운영 진단을 위해 제공하는 엔드포인트 묶음.
 * - 비즈니스 기능이 아니라, "서비스가 살아있나/준비됐나"를 외부가 확인하기 위한 목적
 * 
 * 1) Liveness Probe: "프로세스가 살아있는가?" 
 *  - GET: /actuator/health/liveness -> 200 + JSON + UP
 * 2) Readiness Probe: "트래픽을 받아도 되는 준비 상태인가?"
 *  - GET: /actuator/health/readiness -> 200 + JSON + UP
 * 
 * - Probe는 kubelet이 내부적으로 주기적인 호출이 있기 때문에, Spring Security가 이 요청을 막으면 안된다.
 *    만약 해당 URL이 막히면 K8s는 "앱이 죽었다/준비 안 됐다"로 판단해 재시작하거나 트래픽을 차단한다.
 * 
 * - health는 최소한만 "익명 허용"으로 열어야 한다. 
 *    actuator 전체를 열면(예: env, beans, metrics 등) 정보 노출 위험이 커진다.
 * 
 * - Actuator는 버전/설정에 따라 application/vnd.spring-boot.actuator.v3+json 처럼 vendor media type(+json suffix)을 내려줄 수 있다. 
 *    그래서 "+json suffix"까지 허용하는 media type으로 호환성을 확보한다.
 */
@DisplayName("[Actuator] health 체크")
public class ActuatorHealthIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static final MediaType ANY_JSON_PLUS = MediaType.parseMediaType("application/*+json");

    @Test
    @DisplayName("GET /actuator/health -> 200 + JSON + UP")
    void health_up() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ANY_JSON_PLUS))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /actuator/health/liveness -> 200 + JSON + UP")
    void health_liveness_up() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ANY_JSON_PLUS))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /actuator/health/readiness -> 200 + JSON + UP")
    void health_readiness_up() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ANY_JSON_PLUS))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
