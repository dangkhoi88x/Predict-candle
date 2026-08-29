package com.example.candles.auth;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletSignatureVerifierTest {

    private final WalletSignatureVerifier verifier = new WalletSignatureVerifier();

    @Test
    void acceptsAValidSignatureFromTheClaimedAddress() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair(new SecureRandom());
        String address = "0x" + Keys.getAddress(keyPair);
        String message = "Đăng nhập vào Candle Guess\n\nĐịa chỉ ví: " + address + "\nNonce: abc123";

        String signatureHex = sign(message, keyPair);

        assertTrue(verifier.matches(message, signatureHex, address));
    }

    @Test
    void rejectsASignatureFromADifferentKey() throws Exception {
        ECKeyPair signer = Keys.createEcKeyPair(new SecureRandom());
        ECKeyPair claimedOwner = Keys.createEcKeyPair(new SecureRandom());
        String claimedAddress = "0x" + Keys.getAddress(claimedOwner);
        String message = "Đăng nhập vào Candle Guess\n\nNonce: abc123";

        String signatureHex = sign(message, signer);

        assertFalse(verifier.matches(message, signatureHex, claimedAddress));
    }

    @Test
    void rejectsWhenTheSignedMessageDoesNotMatch() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair(new SecureRandom());
        String address = "0x" + Keys.getAddress(keyPair);

        String signatureHex = sign("original message", keyPair);

        assertFalse(verifier.matches("tampered message", signatureHex, address));
    }

    private String sign(String message, ECKeyPair keyPair) {
        Sign.SignatureData signatureData = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
        byte[] combined = new byte[65];
        System.arraycopy(signatureData.getR(), 0, combined, 0, 32);
        System.arraycopy(signatureData.getS(), 0, combined, 32, 32);
        combined[64] = signatureData.getV()[0];
        return Numeric.toHexString(combined);
    }
}
