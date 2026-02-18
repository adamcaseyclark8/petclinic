/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.rest.controller;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST Assured integration tests for {@link VetRestController}.
 *
 * Unlike the MockMvc tests, these tests boot the full Spring application on a
 * random port and send real HTTP requests over the network.  The database is
 * an in-memory H2 instance seeded with data.sql, so the seed rows (6 vets,
 * 3 specialties, …) are always present at the start of each test run.
 *
 * Key REST Assured concepts demonstrated here:
 *
 *   given()  – set up headers, body, auth, query params, etc.
 *   when()   – specify the HTTP method and path
 *   then()   – assert on status code, headers, and body using Hamcrest matchers
 *   extract() – pull values out of the response for further use in the test
 *
 * Auth note: the test application.properties enables Basic Auth, so every
 * request uses the seed admin account (admin / admin).  By calling
 *   RestAssured.authentication = preemptive().basic(...)
 * in @BeforeEach we set a global default so individual tests don't repeat it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    // Use H2 in-memory database for the integration tests
    "spring.profiles.active=h2,spring-data-jpa",
    "spring.sql.init.platform=h2",
    "spring.sql.init.mode=always",
    // Use a dedicated in-memory DB so this context does not share state with
    // other H2-based test contexts that run in the same JVM (DB_CLOSE_DELAY=-1
    // keeps the named database alive for the full JVM lifetime, which means
    // multiple Spring application contexts would otherwise share the same DB
    // and the second context to start would fail because the schema/data SQL
    // tries to insert rows that already exist).
    "spring.datasource.url=jdbc:h2:mem:petclinic_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class VetRestControllerRestAssuredTests {

    // Spring injects the random port that was chosen at startup
    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        // Tell REST Assured where the server is running.
        // basePath is the servlet context-path from application.properties.
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        RestAssured.basePath = "/petclinic";

        // The test profile enables Basic Auth.  Preemptive auth sends the
        // Authorization header on the first request rather than waiting for a
        // 401 challenge, which avoids an unnecessary round-trip.
        RestAssured.authentication = RestAssured.preemptive().basic("admin", "admin");

        // The JVM is configured with an outbound HTTP proxy (for container network
        // egress).  REST Assured's Groovy HTTP Builder uses the older
        // AbstractHttpClient API and can pick up the JVM system proxy, routing
        // localhost requests to the external proxy (which can't reach localhost).
        // Using DefaultHttpClient (an AbstractHttpClient subclass that does NOT
        // read Java system proxy properties) ensures direct connections to the
        // local test server.
        RestAssured.config = RestAssuredConfig.config().httpClient(
            HttpClientConfig.httpClientConfig()
                .httpClientFactory(() -> new org.apache.http.impl.client.DefaultHttpClient()) // NOSONAR deprecated but required by REST Assured's Groovy HTTP Builder
        );
    }

    // -------------------------------------------------------------------------
    // GET /api/vets  – list all vets
    // -------------------------------------------------------------------------

    @Test
    void getAllVets_returnsOkWithListOfVets() {
        // The seed data has 6 vets, so we expect an array with at least one element.
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            // The seed data has 6 vets; other tests may add more, so use >=
            .body("size()", greaterThanOrEqualTo(6))
            // Spot-check the first vet's name (seed order: James Carter)
            .body("[0].firstName", equalTo("James"))
            .body("[0].lastName", equalTo("Carter"));
    }

    // -------------------------------------------------------------------------
    // GET /api/vets/{id}  – get a single vet
    // -------------------------------------------------------------------------

    @Test
    void getVetById_returnsVet() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets/1")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(1))
            .body("firstName", equalTo("James"))
            .body("lastName", equalTo("Carter"))
            // specialties is a list; James Carter has none in the seed data
            .body("specialties", empty());
    }

    @Test
    void getVetById_withSpecialties_returnsVetWithSpecialties() {
        // Seed data: vet 2 (Helen Leary) has specialty 1 (radiology)
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets/2")
        .then()
            .statusCode(200)
            .body("firstName", equalTo("Helen"))
            .body("specialties", hasSize(1))
            .body("specialties[0].name", equalTo("radiology"));
    }

    @Test
    void getVetById_whenNotFound_returns404() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets/9999")
        .then()
            .statusCode(404);
    }

    // -------------------------------------------------------------------------
    // POST /api/vets  – create a new vet
    // -------------------------------------------------------------------------

    @Test
    void createVet_withValidBody_returnsCreatedWithId() {
        // Using a text block (Java 15+) to keep JSON readable inline.
        String body = """
                {
                    "firstName": "Alice",
                    "lastName":  "Smith",
                    "specialties": []
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/vets")
        .then()
            // 201 Created is the expected status for a successful POST
            .statusCode(201)
            .body("id", notNullValue())
            .body("firstName", equalTo("Alice"))
            .body("lastName", equalTo("Smith"));
    }

    @Test
    void createVet_withMissingFirstName_returnsBadRequest() {
        // firstName is @NotBlank in the domain model, so this should fail validation
        String body = """
                {
                    "lastName": "Smith",
                    "specialties": []
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/vets")
        .then()
            .statusCode(400);
    }

    @Test
    void createVet_withBlankLastName_returnsBadRequest() {
        String body = """
                {
                    "firstName": "Alice",
                    "lastName":  "",
                    "specialties": []
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/vets")
        .then()
            .statusCode(400);
    }

    // -------------------------------------------------------------------------
    // PUT /api/vets/{id}  – update an existing vet
    // -------------------------------------------------------------------------

    @Test
    void updateVet_withValidBody_returnsNoContent() {
        // Vet 3 (Linda Douglas) exists in seed data; we update the last name.
        String body = """
                {
                    "id":        3,
                    "firstName": "Linda",
                    "lastName":  "Douglas-Updated",
                    "specialties": []
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(body)
        .when()
            .put("/api/vets/3")
        .then()
            // 204 No Content is the expected status for a successful PUT
            .statusCode(204);

        // Verify the change was persisted
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets/3")
        .then()
            .statusCode(200)
            .body("lastName", equalTo("Douglas-Updated"));
    }

    @Test
    void updateVet_withBlankFirstName_returnsBadRequest() {
        String body = """
                {
                    "id":        1,
                    "firstName": "",
                    "lastName":  "Carter",
                    "specialties": []
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body(body)
        .when()
            .put("/api/vets/1")
        .then()
            .statusCode(400);
    }

    // -------------------------------------------------------------------------
    // DELETE /api/vets/{id}  – delete a vet
    // -------------------------------------------------------------------------

    @Test
    void deleteVet_whenExists_returnsNoContent() {
        // First create a vet specifically for this test so we don't
        // disturb seed data that other tests rely on.
        String body = """
                {
                    "firstName": "ToDelete",
                    "lastName":  "Me",
                    "specialties": []
                }
                """;

        // extract() lets us pull values out of the response for later use
        Integer createdId = given()
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(body)
            .when()
                .post("/api/vets")
            .then()
                .statusCode(201)
                .extract()
                // path() evaluates a JsonPath expression against the response body
                .path("id");

        // Now delete the newly created vet
        given()
        .when()
            .delete("/api/vets/" + createdId)
        .then()
            .statusCode(204);

        // Confirm it is gone
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/vets/" + createdId)
        .then()
            .statusCode(404);
    }

    @Test
    void deleteVet_whenNotFound_returns404() {
        given()
        .when()
            .delete("/api/vets/9999")
        .then()
            .statusCode(404);
    }
}
