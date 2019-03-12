package com.quorum.tessera.test.rest;

import com.quorum.tessera.api.model.ResendRequest;
import com.quorum.tessera.api.model.ResendRequestType;
import com.quorum.tessera.enclave.EncodedPayload;
import com.quorum.tessera.enclave.PayloadEncoder;
import com.quorum.tessera.enclave.PayloadEncoderImpl;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

import com.quorum.tessera.test.Party;
import com.quorum.tessera.test.PartyHelper;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.Arrays;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.Before;

public class ResendAllIT {

    private byte[] transactionData = UUID.randomUUID().toString().getBytes();

    private final Client client = ClientBuilder.newClient();

    private static final String RESEND_PATH = "/resend";

    private static final PayloadEncoder ENCODER = new PayloadEncoderImpl();

    private PartyHelper partyHelper = PartyHelper.create();

    private Party partyOne;

    private Party partyTwo;

    private Party partyThree;

    @Before
    public void onSetup() {
        this.partyOne = partyHelper.findByAlias("A");
        this.partyTwo = partyHelper.findByAlias("B");
        this.partyThree = partyHelper.findByAlias("C");
    }

    @Test
    public void resendTransactionsForGivenKey() throws UnsupportedEncodingException {
        //setup (sending in a tx)

        Response sendRawResponse = client.target(partyOne.getQ2TUri())
                .path("/sendraw")
                .request()
                .header("c11n-from", partyOne.getPublicKey())
                .header("c11n-to", partyTwo.getPublicKey())
                .post(Entity.entity(transactionData, MediaType.APPLICATION_OCTET_STREAM));

        URI location = sendRawResponse.getLocation();

        String hash = sendRawResponse.readEntity(String.class);

        final String encodedHash = URLEncoder.encode(hash, UTF_8.toString());

        //delete it from the recipient node
        final Response deleteReq = client.target(location)
                .request()
                .delete();

        assertThat(deleteReq).isNotNull();
        assertThat(deleteReq.getStatus()).isEqualTo(204);

        //check it is deleted
        final Response deleteCheck = client.target(partyOne.getQ2TUri())
                .path("transaction")
                .path(encodedHash)
                .request()
                .get();

        assertThat(deleteCheck).isNotNull();
        assertThat(deleteCheck.getStatus()).isEqualTo(404);

        //perform resend
        final ResendRequest req = new ResendRequest();
        req.setType(ResendRequestType.ALL);
        req.setPublicKey(partyTwo.getPublicKey());

        final Response resendRequest = client.target(partyOne.getP2PUri())
                .path(RESEND_PATH)
                .request()
                .buildPost(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE))
                .invoke();

        assertThat(resendRequest).isNotNull();
        assertThat(resendRequest.getStatus()).isEqualTo(200);

        //and fetch the transaction to make sure it is there
        final Response resendCheck = client.target(partyTwo.getQ2TUri())
                .path("transaction")
                .path(encodedHash)
                .request()
                .get();

        assertThat(resendCheck).isNotNull();
        assertThat(resendCheck.getStatus()).isEqualTo(200);

    }

    @Test
    public void transactionsAreReconstructedFromMultipleParties() throws UnsupportedEncodingException, SQLException {
        //setup (sending in a tx)

        String anyOtherPartyKey = partyHelper.getParties().filter(p -> !Arrays.asList(partyOne, partyTwo)
                .contains(p))
                .findAny()
                .get()
                .getPublicKey();

        Response sendRawResponse = client.target(partyOne.getQ2TUri())
                .path("/sendraw")
                .request()
                .header("c11n-from", partyOne.getPublicKey())
                .header("c11n-to", partyTwo.getPublicKey() + "," + anyOtherPartyKey)
                .post(Entity.entity(transactionData, MediaType.APPLICATION_OCTET_STREAM));

        URI location = sendRawResponse.getLocation();
        String hash = sendRawResponse.readEntity(String.class);

        final String encodedHash = URLEncoder.encode(hash, UTF_8.toString());

        //delete it from the recipient node
        final Response deleteReq = client.target(location)
                .request()
                .delete();
        assertThat(deleteReq).isNotNull();
        assertThat(deleteReq.getStatus()).isEqualTo(204);

        //check it is deleted
        final Response deleteCheck = client.target(partyOne.getQ2TUri())
                .path("transaction")
                .path(encodedHash)
                .request()
                .get();

        assertThat(deleteCheck).isNotNull();
        assertThat(deleteCheck.getStatus()).isEqualTo(404);

        //perform resend
        final ResendRequest req = new ResendRequest();
        req.setType(ResendRequestType.ALL);
        req.setPublicKey(partyOne.getPublicKey());

        final Response resendRequest = client.target(partyTwo.getP2PUri())
                .path(RESEND_PATH)
                .request()
                .buildPost(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE))
                .invoke();

        assertThat(resendRequest).isNotNull();
        assertThat(resendRequest.getStatus()).isEqualTo(200);

        final Response resendRequestNode3 = client.target(partyThree.getP2PUri())
                .path(RESEND_PATH)
                .request()
                .buildPost(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE))
                .invoke();

        assertThat(resendRequestNode3).isNotNull();
        assertThat(resendRequestNode3.getStatus()).isEqualTo(200);

        final String fetch = "SELECT ENCODED_PAYLOAD FROM ENCRYPTED_TRANSACTION WHERE HASH = ?";
        final Connection databaseConnection = PartyHelper.create().findByPublicKey(partyOne.getPublicKey()).getDatabaseConnection();
        try (PreparedStatement statement = databaseConnection.prepareStatement(fetch)){
            statement.setBytes(1, Base64.getDecoder().decode(hash));
            try (ResultSet rs = statement.executeQuery()){
                assertThat(rs.next()).isTrue();
                final byte[] output = rs.getBytes(1);
                final EncodedPayload payload = ENCODER.decode(output);
                assertThat(payload.getRecipientKeys()).hasSize(3);
                assertThat(payload.getSenderKey().encodeToBase64()).isEqualTo(partyOne.getPublicKey());
                assertThat(payload.getRecipientBoxes()).hasSize(3);
            }
        }

    }

    @Test
    public void resendForKeyWithNoTransactions() {
        //perform resend
        final ResendRequest req = new ResendRequest();
        req.setType(ResendRequestType.ALL);
        req.setPublicKey("rUSW9gnm2Unm5ECvEfuU10LX7KYsN59Flw7m7iu6wEo=");

        final Response resendRequest = client.target(partyOne.getP2PUri())
                .path(RESEND_PATH)
                .request()
                .buildPost(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE))
                .invoke();

        assertThat(resendRequest).isNotNull();
        assertThat(resendRequest.getStatus()).isEqualTo(200);
    }

    @Test
    public void resendForInvalidKeyErrors() {
        //perform resend
        final ResendRequest req = new ResendRequest();
        req.setType(ResendRequestType.ALL);
        req.setPublicKey("rUSW9gnm2Unm5ECvEfuU&&&&&&&&59Flw7m7iu6wEo=");

        final Response resendRequest = client.target(partyOne.getP2PUri())
                .path(RESEND_PATH)
                .request()
                .buildPost(Entity.entity(req, MediaType.APPLICATION_JSON_TYPE))
                .invoke();

        assertThat(resendRequest).isNotNull();
        assertThat(resendRequest.getStatus()).isEqualTo(400);
    }

}