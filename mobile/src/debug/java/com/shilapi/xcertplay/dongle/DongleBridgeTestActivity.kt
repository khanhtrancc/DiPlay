package com.shilapi.xcertplay.dongle

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.SocketDuplexByteStream
import java.io.File
import java.net.Socket

class DongleBridgeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port", 19876)
        Thread { runTest(port) }.start()
        finish()
    }

    private fun runTest(port: Int) {
        log("dongle bridge test starting, connecting to localhost:$port")
        try {
            val mfiDir = File(applicationContext.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
            log("loading MFi from $mfiDir exists=${mfiDir.isDirectory}")
            val mfi = LocalMfiAuthenticationClient.load(mfiDir) { size ->
                log("mfi signature requested size=$size")
            }
            log("MFi loaded: protocolMajor=${mfi.protocolMajor()}")

            val socket = Socket("127.0.0.1", port)
            socket.tcpNoDelay = true
            log("connected to bridge socket")

            val stream = SocketDuplexByteStream(socket)
            val session = Iap2Session.openWireless(
                underlying = stream,
                traceContext = "dongle-bridge",
                onTrace = { log("iap2: $it") },
            )
            log("iAP2 session opened, waiting for link ready")

            if (!session.awaitReady(15_000)) {
                log("ERROR: iAP2 link not ready after 15s")
                session.close()
                return
            }
            log("iAP2 link ready")

            val firstMessage = session.recv(30_000)
            if (firstMessage == null) {
                log("ERROR: no message from dongle after 30s")
                session.close()
                return
            }
            log("first message: id=0x${firstMessage.messageId.hex()} payload=${firstMessage.payload.size}B")

            when (firstMessage.messageId) {
                Iap2IdentificationClient.START_IDENTIFICATION -> {
                    log("dongle sent StartIdentification — sending IdentificationInformation")
                    session.send(Iap2IdentificationClient.identificationInformation(identificationConfig()), 10_000)
                    val idResp = session.recv(15_000)
                        ?: throw RuntimeException("no identification response")
                    log("identification response: 0x${idResp.messageId.hex()}")
                    if (idResp.messageId == Iap2IdentificationClient.IDENTIFICATION_REJECTED) {
                        throw RuntimeException("identification rejected")
                    }
                    log("identification accepted, running MFi auth")
                    val iap2Mfi = Iap2MfiAuthenticationClient(mfi)
                    iap2Mfi.run(session, 30_000) { log("mfi: $it") }
                    log("*** MFi AUTHENTICATION SUCCEEDED ***")
                    runCarPlayControl(session)
                }

                REQUEST_CERTIFICATE -> {
                    log("dongle sent RequestAuthenticationCertificate directly — running MFi auth")
                    handleDirectMfiAuth(session, mfi)
                    log("*** MFi AUTHENTICATION SUCCEEDED ***")
                    runCarPlayControl(session)
                }

                else -> {
                    log("unexpected first message: 0x${firstMessage.messageId.hex()}")
                }
            }
            log("test complete, keeping session open for 120s to observe traffic")
            drainMessages(session, 120_000)
            session.close()
        } catch (e: Exception) {
            log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleDirectMfiAuth(session: Iap2Session, mfi: LocalMfiAuthenticationClient) {
        val cert = mfi.readCertificate(4096)
        log("sending MFi certificate (${cert.size} bytes)")
        session.send(
            com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages.accessoryCertificate(cert),
            10_000,
        )
        while (true) {
            val msg = session.recv(30_000) ?: throw RuntimeException("timeout waiting for MFi")
            log("mfi message: 0x${msg.messageId.hex()} payload=${msg.payload.size}B")
            when (msg.messageId) {
                REQUEST_CHALLENGE -> {
                    val challenge = msg.body().asList().firstOrNull()?.payload
                        ?: throw RuntimeException("no challenge data")
                    log("signing challenge (${challenge.size} bytes)")
                    val signature = mfi.signChallenge(challenge)
                    session.send(
                        com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages.response(signature),
                        10_000,
                    )
                }
                AUTHENTICATION_SUCCEEDED -> return
                AUTHENTICATION_FAILED -> throw RuntimeException("MFi authentication FAILED")
            }
        }
    }

    private fun runCarPlayControl(session: Iap2Session) {
        log("=== PHASE: Power + Subscriptions ===")
        session.send(Iap2WiredControlClient.powerSourceUpdate(500), 10_000)
        log("sent PowerSourceUpdate (500mA)")

        for (sub in Iap2WiredControlClient.subscriptions()) {
            session.send(sub, 10_000)
            log("sent subscription 0x${sub.messageId.hex()}")
        }
        log("all subscriptions sent")

        log("=== PHASE: Waiting for CarPlay messages ===")
        val identity = AirPlayIdentity.generate()
        log("generated AirPlay identity: pubkey=${identity.publicKeyHex.take(16)}...")

        val endpoint = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            publicKey = identity.publicKeyHex,
            sourceVersion = "950.7.1",
            deviceIdentifier = "AA:BB:CC:DD:EE:FF",
        )

        val deadline = System.currentTimeMillis() + 60_000
        var startSessionSent = false
        while (System.currentTimeMillis() < deadline) {
            val msg = session.recv(5_000) ?: continue
            log("rx 0x${msg.messageId.hex()} payload=${msg.payload.size}B")

            when (msg.messageId) {
                CARPLAY_AVAILABILITY -> {
                    log("CarPlay availability received")
                    logAvailability(msg)
                    if (!startSessionSent) {
                        val startSession = Iap2CarPlayMessages.startSession(
                            wiredIpv6Addresses = endpoint.ipv6Addresses,
                            airPlayPort = endpoint.airPlayPort,
                            publicKey = endpoint.publicKey,
                            sourceVersion = endpoint.sourceVersion,
                            deviceIdentifier = endpoint.deviceIdentifier,
                        )
                        session.send(startSession, 10_000)
                        startSessionSent = true
                        log("*** SENT CarPlayStartSession (0x4301) ***")
                        log("  ipv6=${endpoint.ipv6Addresses}, port=${endpoint.airPlayPort}")
                        log("  deviceId=${endpoint.deviceIdentifier}")
                    }
                }

                else -> {
                    log("  (unhandled message 0x${msg.messageId.hex()})")
                }
            }
        }
        if (!startSessionSent) {
            log("WARNING: never received CarPlayAvailability in 60s")
        }
    }

    private fun logAvailability(frame: Iap2Frame) {
        try {
            val summary = Iap2WiredControlClient.carPlayAvailabilitySummary(frame.payload)
            log("  $summary")
        } catch (e: Exception) {
            log("  availability decode error: ${e.message}")
        }
    }

    private fun drainMessages(session: Iap2Session, durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline && !session.isClosed) {
            val msg = session.recv(5_000) ?: continue
            log("drain rx 0x${msg.messageId.hex()} payload=${msg.payload.size}B")
        }
    }

    private fun identificationConfig() = Iap2IdentificationConfig(
        name = "DiPlay",
        modelIdentifier = "DiPlay1,1",
        manufacturer = "DiPlay",
        serialNumber = "DIPLAY0001",
        firmwareVersion = "1.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 0,
    )

    companion object {
        private const val TAG = "DongleBridge"
        private const val REQUEST_CERTIFICATE = 0xaa00
        private const val REQUEST_CHALLENGE = 0xaa02
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val AUTHENTICATION_FAILED = 0xaa04
        private const val CARPLAY_AVAILABILITY = 0x4300

        private fun log(msg: String) = Log.i(TAG, msg)
        private fun Int.hex() = toString(16).padStart(4, '0')
    }
}
