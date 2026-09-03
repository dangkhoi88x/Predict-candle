package com.example.candles.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import com.example.candles.dto.WalletVerifyRequest;
import com.example.candles.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with a wallet, over HTTP, end to end.
 *
 * WalletSignatureVerifierTest already covers whether a signature recovers to an address. What
 * had no coverage at all was the flow around it — issuing a nonce, spending it, minting a
 * session — which is the only way anyone gets an account here. A break in it locks every player
 * out, and until now nothing would have noticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WalletLoginFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;

    private final ObjectMapper mapper = new ObjectMapper();

    private record Wallet(ECKeyPair keys, String address) {
    }

    private Wallet newWallet() throws Exception {
        ECKeyPair keys = Keys.createEcKeyPair(new SecureRandom());
        return new Wallet(keys, "0x" + Keys.getAddress(keys));
    }

    /** Exactly what a wallet extension does with `personal_sign`. */
    private String sign(String message, ECKeyPair keys) {
        Sign.SignatureData data = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keys);
        byte[] combined = new byte[65];
        System.arraycopy(data.getR(), 0, combined, 0, 32);
        System.arraycopy(data.getS(), 0, combined, 32, 32);
        combined[64] = data.getV()[0];
        return Numeric.toHexString(combined);
    }

    private JsonNode requestNonce(String address) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/wallet/nonce?address=" + address))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult verify(String address, String signature) throws Exception {
        return mockMvc.perform(post("/api/auth/wallet/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new WalletVerifyRequest(address, signature))))
                .andReturn();
    }

    @Test
    void signingTheServersMessageCreatesAnAccountAndASession() throws Exception {
        Wallet wallet = newWallet();
        assertThat(users.findByWalletAddress(wallet.address())).isEmpty();

        JsonNode challenge = requestNonce(wallet.address());
        // The message is the server's to compose — a client that signed its own wording could
        // be replaying something a user agreed to somewhere else entirely.
        assertThat(challenge.path("message").asString()).contains(challenge.path("nonce").asString());

        MvcResult result = verify(wallet.address(), sign(challenge.path("message").asString(), wallet.keys()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode session = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(session.path("accessToken").asString()).isNotBlank();
        assertThat(session.path("role").asString()).isEqualTo("USER");

        // First sign-in is also registration, and the wallet is the identity.
        assertThat(users.findByWalletAddress(wallet.address())).isPresent();

        /* The refresh token is an HttpOnly cookie and the access token is not stored at all —
           that pairing is what keeps a token out of reach of any script on the page. */
        assertThat(result.getResponse().getCookies())
                .anySatisfy(cookie -> assertThat(cookie.isHttpOnly()).isTrue());
    }

    @Test
    void aNonceIsSpentOnceAndCannotBeReplayed() throws Exception {
        Wallet wallet = newWallet();
        JsonNode challenge = requestNonce(wallet.address());
        String signature = sign(challenge.path("message").asString(), wallet.keys());

        assertThat(verify(wallet.address(), signature).getResponse().getStatus()).isEqualTo(200);

        // The same signature is a valid signature forever. Only the nonce being consumed stops
        // anyone who captured it from signing in again with it.
        assertThat(verify(wallet.address(), signature).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aSignatureFromAnotherWalletCannotClaimTheAddress() throws Exception {
        Wallet owner = newWallet();
        Wallet impostor = newWallet();
        JsonNode challenge = requestNonce(owner.address());

        // Correct message, correct address in the request, signed by the wrong key.
        int status = verify(owner.address(),
                sign(challenge.path("message").asString(), impostor.keys()))
                .getResponse().getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(users.findByWalletAddress(owner.address())).isEmpty();
    }

    @Test
    void verifyingWithoutEverAskingForANonceIsRejected() throws Exception {
        Wallet wallet = newWallet();

        // A plausible-looking message the server never issued.
        String invented = "Đăng nhập vào Candle Guess\n\nĐịa chỉ ví: " + wallet.address() + "\nNonce: deadbeef";

        assertThat(verify(wallet.address(), sign(invented, wallet.keys()))
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void anAddressThatIsNotAnAddressIsRefusedBeforeAnyWorkHappens() throws Exception {
        mockMvc.perform(get("/api/auth/wallet/nonce?address=not-a-wallet"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meNeedsASessionAndReportsTheSignedInWallet() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        Wallet wallet = newWallet();
        JsonNode challenge = requestNonce(wallet.address());
        JsonNode session = mapper.readTree(verify(wallet.address(),
                sign(challenge.path("message").asString(), wallet.keys()))
                .getResponse().getContentAsString());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + session.path("accessToken").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value(wallet.address()));
    }
}
