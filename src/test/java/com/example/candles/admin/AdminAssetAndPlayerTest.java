package com.example.candles.admin;

import com.example.candles.auth.JwtService;
import com.example.candles.domain.Role;
import com.example.candles.domain.User;
import com.example.candles.repository.AssetRepository;
import com.example.candles.repository.UserRepository;
import com.example.candles.round.RoundSelectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAssetAndPlayerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private JwtService jwtService;
    @Autowired private AdminAssetService assetService;
    @Autowired private AdminPlayerService playerService;
    @Autowired private RoundSelectionService roundSelectionService;

    private User save(Role role, String name) {
        User user = new User("0x" + UUID.randomUUID().toString().replace("-", ""), name);
        user.assignRole(role);
        userRepository.saveAndFlush(user);
        return user;
    }

    private String token(Role role) {
        return "Bearer " + jwtService.createAccessToken(save(role, "T"));
    }

    @Test
    void aNewPairArrivesDisabledAndCannotBeEnabledWithoutCandles() {
        var created = assetService.create("adausdt", "Cardano");

        assertThat(created.symbol()).isEqualTo("ADAUSDT");
        assertThat(created.enabled()).isFalse();
        assertThat(created.candles()).isZero();

        // Enabling an empty pair would put a chart in the picker that no round can be built
        // from, so it is refused until a backfill has run.
        assertThatThrownBy(() -> assetService.setEnabled(created.id(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chưa có nến");
    }

    @Test
    void theOrderInThePickerIsTheOrderInTheAdminList() {
        var before = assetService.assets();
        assertThat(before.getFirst().symbol()).isEqualTo("BTCUSDT");

        var moved = assetService.move(before.get(1).id(), true);
        assertThat(moved.getFirst().symbol()).isEqualTo(before.get(1).symbol());
        // Positions are rewritten from the visible order, so no two rows share one.
        assertThat(moved.stream().map(AdminAssetService.AssetSummary::position).distinct().count())
                .isEqualTo(moved.size());

        assetService.move(moved.get(1).id(), true);
        assertThat(assetService.assets().getFirst().symbol()).isEqualTo("BTCUSDT");
    }

    @Test
    void movingPastTheEndDoesNothing() {
        var before = assetService.assets();
        assertThat(assetService.move(before.getFirst().id(), true))
                .extracting(AdminAssetService.AssetSummary::symbol)
                .isEqualTo(before.stream().map(AdminAssetService.AssetSummary::symbol).toList());
    }

    @Test
    void aNewPairSortsAfterTheExistingOnes() {
        assetService.create("xrpusdt", "XRP");
        assertThat(assetService.assets().getLast().symbol()).isEqualTo("XRPUSDT");
    }

    @Test
    void malformedOrDuplicatePairsAreRejected() {
        assertThatThrownBy(() -> assetService.create("ada usdt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assetService.create("BTCUSDT", "Bitcoin lần hai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã có rồi");
    }

    @Test
    void aDisabledPairIsNotPlayableEvenIfAskedForDirectly() {
        var asset = assetRepository.findBySymbol("BTCUSDT").orElseThrow();
        asset.setEnabled(false);
        assetRepository.saveAndFlush(asset);

        // The picker is a list in a browser; the round endpoint takes whatever it is given.
        assertThatThrownBy(() -> roundSelectionService.resolveAsset("BTCUSDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tạm tắt");

        asset.setEnabled(true);
        assetRepository.saveAndFlush(asset);
    }

    @Test
    void publicAssetListOffersOnlyEnabledPairs() throws Exception {
        var created = assetService.create("dotusdt", "Polkadot");

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.symbol == 'DOTUSDT')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.symbol == 'BTCUSDT')].shortSymbol").value("BTC"));
        assertThat(created.enabled()).isFalse();
    }

    @Test
    void playersCanBeRenamedButNotPromoted() throws Exception {
        User player = save(Role.USER, "Tên cũ");

        var renamed = playerService.rename(player.getId(), "  Tên mới  ");
        assertThat(renamed.displayName()).isEqualTo("Tên mới");
        assertThat(renamed.role()).isEqualTo("USER");

        assertThatThrownBy(() -> playerService.rename(player.getId(), "   "))
                .isInstanceOf(IllegalArgumentException.class);

        // There is no endpoint for it at all — the role is configuration, not data.
        mockMvc.perform(put("/api/admin/players/" + player.getId() + "/role")
                        .header("Authorization", token(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAdminAccountCannotBeDeletedFromHere() {
        User admin = save(Role.ADMIN, "Quản trị");

        assertThatThrownBy(() -> playerService.delete(admin.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candles.admin.wallets");
    }

    @Test
    void bothScreensAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/assets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/players")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/players").header("Authorization", token(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/players/1").header("Authorization", token(Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/assets").header("Authorization", token(Role.USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"XRPUSDT\"}"))
                .andExpect(status().isForbidden());
    }
}
