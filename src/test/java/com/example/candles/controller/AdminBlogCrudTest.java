package com.example.candles.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

import com.example.candles.entity.Role;
import com.example.candles.entity.User;
import com.example.candles.repository.BlogPostRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminBlogCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private BlogPostRepository blogPostRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String playerToken;

    @BeforeEach
    void accounts() {
        User admin = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "Admin");
        admin.assignRole(Role.ADMIN);
        User player = new User("0x" + UUID.randomUUID().toString().replace("-", ""), "Player");
        userRepository.saveAndFlush(admin);
        userRepository.saveAndFlush(player);
        adminToken = "Bearer " + jwtService.createAccessToken(admin);
        playerToken = "Bearer " + jwtService.createAccessToken(player);
    }

    private static final String BODY = """
            {"title":"Bài kiểm thử","tags":["Test","Mẫu"],
             "source":"Nguồn","sourceUrl":"https://example.com/x","imageCredit":null,
             "coverSvg":null,"coverImg":"https://example.com/cover.png",
             "body":[{"type":"text","text":"Đoạn một"},
                     {"type":"image","src":"https://example.com/a.png","w":800,"h":450,"alt":"mô tả"}],
             "published":false,"position":9}
            """;

    @Test
    void createReadUpdateDelete() throws Exception {
        String created = mockMvc.perform(post("/api/admin/blog/posts")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                // The slug is derived from the Vietnamese title with the diacritics folded away.
                .andExpect(jsonPath("$.slug").value("bai-kiem-thu"))
                .andExpect(jsonPath("$.body.length()").value(2))
                .andExpect(jsonPath("$.body[1].w").value(800))
                .andExpect(jsonPath("$.tags[0]").value("Test"))
                .andExpect(jsonPath("$.published").value(false))
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(created).get("id").asLong();

        // A draft is in the admin listing and out of the public one.
        mockMvc.perform(get("/api/admin/blog/posts").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).exists());
        mockMvc.perform(get("/api/blog/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).doesNotExist());

        mockMvc.perform(put("/api/admin/blog/posts/" + id)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"published\":false", "\"published\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                // Same title, so the slug it already holds must not be treated as a clash.
                .andExpect(jsonPath("$.slug").value("bai-kiem-thu"));

        mockMvc.perform(get("/api/blog/posts"))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).exists());

        mockMvc.perform(delete("/api/admin/blog/posts/" + id).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        assertThat(blogPostRepository.findById(id)).isEmpty();
    }

    @Test
    void secondPostWithTheSameTitleGetsItsOwnSlug() throws Exception {
        mockMvc.perform(post("/api/admin/blog/posts").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.slug").value("bai-kiem-thu"));
        mockMvc.perform(post("/api/admin/blog/posts").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.slug").value("bai-kiem-thu-2"));
    }

    @Test
    void writesAreClosedToEveryoneElse() throws Exception {
        mockMvc.perform(post("/api/admin/blog/posts")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/blog/posts").header("Authorization", playerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/admin/blog/posts/1").header("Authorization", playerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPostWithoutATitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/blog/posts").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"title\":\"Bài kiểm thử\"", "\"title\":\"  \"")))
                .andExpect(status().isBadRequest());
    }

    /**
     * V12 rewrote every body from a flat block array into a ProseMirror document. The posts
     * are the only content this app ships with, and a conversion that quietly emptied one
     * would show up as a blank article rather than as an error, so this checks the shape and
     * that both kinds of block came through — not merely that the column parses.
     */
    @Test
    void seededPostsSurvivedTheMoveToProseMirror() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(blogPostRepository.findByPublishedTrueOrderByPositionAscIdAsc())
                .hasSize(3)
                .allSatisfy(post -> {
                    JsonNode body = mapper.readTree(post.getBody());
                    assertThat(body.path("type").asString()).isEqualTo("doc");

                    JsonNode content = body.path("content");
                    assertThat(content.isArray()).isTrue();
                    assertThat(content).isNotEmpty();

                    long paragraphs = count(content, "paragraph");
                    long images = count(content, "image");
                    assertThat(paragraphs).isPositive();
                    assertThat(images).isPositive();
                    assertThat(paragraphs + images).isEqualTo(content.size());

                    // A paragraph carrying an empty text node is invalid ProseMirror and
                    // throws when the editor loads it; the migration emits no content instead.
                    content.forEach(node -> node.path("content").forEach(child ->
                            assertThat(child.path("text").asString()).isNotEmpty()));

                    // The dimensions are what reserve an image's box on the public page.
                    content.forEach(node -> {
                        if (!"image".equals(node.path("type").asString())) return;
                        assertThat(node.path("attrs").path("src").asString()).isNotBlank();
                        assertThat(node.path("attrs").path("width").asInt()).isPositive();
                        assertThat(node.path("attrs").path("height").asInt()).isPositive();
                    });
                });
    }

    private static long count(JsonNode content, String type) {
        long total = 0;
        for (JsonNode node : content) {
            if (type.equals(node.path("type").asString())) total++;
        }
        return total;
    }
}
