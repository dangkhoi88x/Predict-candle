package com.example.candles.auth;

import org.springframework.stereotype.Component;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.security.SignatureException;

/**
 * Recovers the address that produced an EIP-191 "personal_sign" signature and checks it
 * against the address the client claims to be.
 */
@Component
public class WalletSignatureVerifier {

    public boolean matches(String message, String hexSignature, String claimedAddress) {
        try {
            byte[] signatureBytes = Numeric.hexStringToByteArray(hexSignature);
            if (signatureBytes.length != 65) {
                return false;
            }
            byte[] r = java.util.Arrays.copyOfRange(signatureBytes, 0, 32);
            byte[] s = java.util.Arrays.copyOfRange(signatureBytes, 32, 64);
            byte v = signatureBytes[64];
            if (v < 27) {
                v += 27;
            }
            Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);

            var publicKey = Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), signatureData);
            String recoveredAddress = "0x" + Keys.getAddress(publicKey);
            return recoveredAddress.equalsIgnoreCase(claimedAddress);
        } catch (SignatureException | RuntimeException e) {
            // Malformed hex, invalid EC point, bad recovery id, etc. — all just mean "not a
            // valid signature from this address", not a server error.
            return false;
        }
    }
}
