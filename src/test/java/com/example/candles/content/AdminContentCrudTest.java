package com.example.candles.content;

import com.example.candles.auth.JwtService;
import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Content editing, and the line it must not cross: patterns are backed by matchers in Java,
 * so their wording is editable and their existence is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminContentCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ContentItemRepository repository;

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

    private Long idOf(ContentKind kind) {
        return repository.findByKindOrderByPositionAscIdAsc(kind).getFirst().getId();
    }

    private static String item(String key, String title) {
        return """
                {"itemKey":"%s","title":"%s",
                 "body":{"id":"%s","name":"%s","tags":["neutral"],"summary":"tóm tắt","howTo":["b1"]},
                 "position":0,"published":true}
                """.formatted(key, title, key, title);
    }

    @Test
    void wordingOfAPatternCanBeEdited() throws Exception {
        Long id = idOf(ContentKind.CANDLE_PATTERN);
        mockMvc.perform(put("/api/admin/content/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(item("doji", "Doji (đã sửa)")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doji (đã sửa)"))
                .andExpect(jsonPath("$.itemKey").value("doji"))
                .andExpect(jsonPath("$.editableKey").value(false));
    }

    @Test
    void aPatternCannotBeReKeyed() throws Exception {
        Long id = idOf(ContentKind.CANDLE_PATTERN);
        mockMvc.perform(put("/api/admin/content/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(item("doji-moi", "Doji")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void patternsCannotBeCreatedOrDeleted() throws Exception {
        mockMvc.perform(post("/api/admin/content/candle-pattern").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(item("mau-tu-che", "Mẫu tự chế")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/content/" + idOf(ContentKind.CANDLE_PATTERN))
                        .header("Authorization", adminToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/content/" + idOf(ContentKind.TECHNICAL_PATTERN))
                        .header("Authorization", adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void psychologyNotesAreOrdinaryRows() throws Exception {
        String created = mockMvc.perform(post("/api/admin/content/psychology")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Ghi chú mới","body":{"title":"Ghi chú mới","body":"nội dung"},
                                 "position":99,"published":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.editableKey").value(true))
                .andExpect(jsonPath("$.itemKey").value("ghi-chu-moi"))
                .andReturn().getResponse().getContentAsString();

        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(delete("/api/admin/content/" + id).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void unpublishingHidesAnEntryFromThePublicTab() throws Exception {
        Long id = idOf(ContentKind.PSYCHOLOGY);
        mockMvc.perform(put("/api/admin/content/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Tạm ẩn","body":{"title":"Tạm ẩn","body":"x"},
                                 "position":0,"published":false}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/content/psychology"))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).doesNotExist());
    }

    @Test
    void editingIsClosedToEveryoneElse() throws Exception {
        Long id = idOf(ContentKind.PSYCHOLOGY);
        mockMvc.perform(put("/api/admin/content/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(item("x", "y")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/admin/content/" + id).header("Authorization", playerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(item("x", "y")))
                .andExpect(status().isForbidden());
    }
}
