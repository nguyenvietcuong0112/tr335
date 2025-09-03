package com.example.videochat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Capturer;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VideoChatActivity extends AppCompatActivity {

    private static final String TAG = "VideoChatActivity";
    private static final int PERMISSION_REQUEST_CODE = 123;

    // UI Components
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private Button btnNext;
    private ImageButton btnEndCall;
    private ImageButton btnToggleCamera;
    private ImageButton btnToggleMic;
    private TextView tvStatus;
    private TextView tvTimer;

    // WebRTC Components
    private PeerConnectionFactory peerConnectionFactory;
    private VideoCapturer videoCapturer;
    private VideoSource localVideoSource;
    private VideoTrack localVideoTrack;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;
    private PeerConnection peerConnection;
    private EglBase eglBase;

    // Audio
    private AudioManager audioManager;

    // State
    private boolean isVideoEnabled = true;
    private boolean isAudioEnabled = true;
    private boolean isFrontCamera = true;
    private boolean isConnected = false;
    private boolean hasOffered = false;


    // Signaling
    private SignalingClient signalingClient;
    private String partnerId;   // 🔥 lưu partnerId

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_chat);

        // Init AudioManager
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        initViews();
        setupClickListeners();
        checkPermissions();
    }

    private void initViews() {
        localVideoView = findViewById(R.id.local_video_view);
        remoteVideoView = findViewById(R.id.remote_video_view);
        btnNext = findViewById(R.id.btn_next);
        btnEndCall = findViewById(R.id.btn_end_call);
        btnToggleCamera = findViewById(R.id.btn_toggle_camera);
        btnToggleMic = findViewById(R.id.btn_toggle_mic);
        tvStatus = findViewById(R.id.tv_status);
        tvTimer = findViewById(R.id.tv_timer);
    }

    private void setupClickListeners() {
        btnNext.setOnClickListener(v -> findNextPartner());
        btnEndCall.setOnClickListener(v -> endCall());
        btnToggleCamera.setOnClickListener(v -> toggleCamera());
        btnToggleMic.setOnClickListener(v -> toggleMicrophone());
    }

    private void checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_CODE);
        } else {
            initializeWebRTC();
        }
    }

    private void initializeWebRTC() {
        // Release video views if already initialized
        if (localVideoView != null) localVideoView.release();
        if (remoteVideoView != null) remoteVideoView.release();

        eglBase = EglBase.create();

        // ✅ setup audio mode
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true); // bật loa ngoài
        }


        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        // Video capturer
        createVideoCapturer();

        // Video source + track
        localVideoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoCapturer.initialize(surfaceTextureHelper, this, localVideoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 640, 30);

        localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", localVideoSource);

        // ✅ Audio source + track với AutoGainControl
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));

        localAudioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", localAudioSource);

        // Local video view
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
        localVideoView.setEnableHardwareScaler(true); // thêm dòng này
        localVideoTrack.addSink(localVideoView);

        // Remote video view
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        remoteVideoView.setEnableHardwareScaler(true);

        // PeerConnection
        createPeerConnection();

        // Signaling
        initializeSignaling();
    }

    private void initializeSignaling() {
        signalingClient = new SignalingClient(new SignalingClient.SignalingCallback() {
            @Override
            public void onRemoteDescription(SessionDescription sessionDescription) {
                Log.d(TAG, "[SIGNALING] Got remote description: " + sessionDescription.type);
                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (sessionDescription.type == SessionDescription.Type.OFFER) {
                            peerConnection.createAnswer(new SimpleSdpObserver() {
                                @Override
                                public void onCreateSuccess(SessionDescription answerSdp) {
                                    peerConnection.setLocalDescription(new SimpleSdpObserver(), answerSdp);
                                    signalingClient.sendAnswer(answerSdp);
                                }
                            }, new MediaConstraints());
                        }
                    }
                }, sessionDescription);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                if (peerConnection != null) {
                    peerConnection.addIceCandidate(iceCandidate);
                }
            }

            @Override
            public void onPartnerFound(String partnerId) {
                VideoChatActivity.this.partnerId = partnerId;

                if (partnerId == null) {
                    updateStatus("Waiting for partner...");
                } else {
                    updateStatus("Partner found! ID: " + partnerId);
                    if (signalingClient.isCaller() && !hasOffered) {
                        hasOffered = true; // 🔥 chỉ tạo offer 1 lần
                        createOffer();
                    }
                }
            }

            @Override
            public void onPartnerDisconnected() {
                runOnUiThread(() -> {
                    updateStatus("Partner disconnected");
                    isConnected = false;
                    if (peerConnection != null) peerConnection.close();
                    resetConnectionAndFindNewPartner();
                });
            }
        });
    }

    private void createOffer() {
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                signalingClient.sendOffer(sessionDescription);
            }
        }, new MediaConstraints());
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }

    private void createVideoCapturer() {
        videoCapturer = new Camera2Capturer(this, "0", new CameraVideoCapturer.CameraEventsHandler() {
            @Override public void onCameraError(String errorDescription) { Log.e(TAG, "Camera error: " + errorDescription); }
            @Override public void onCameraDisconnected() {}
            @Override public void onCameraFreezed(String errorDescription) {}
            @Override public void onCameraOpening(String cameraName) {}
            @Override public void onFirstFrameAvailable() {}
            @Override public void onCameraClosed() {}
        });
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate iceCandidate) { signalingClient.sendIceCandidate(iceCandidate); }
            @Override public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack) {
                    VideoTrack remoteVideoTrack = (VideoTrack) track;
                    runOnUiThread(() -> {
                        remoteVideoTrack.addSink(remoteVideoView);
                        updateStatus("Connected!");
                        isConnected = true;
                    });
                }
            }
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddStream(MediaStream stream) {}
        });

        if (peerConnection != null) {
            List<String> streamIds = Collections.singletonList("local_stream");
            peerConnection.addTrack(localVideoTrack, streamIds);
            peerConnection.addTrack(localAudioTrack, streamIds);
        }
    }

    private void findNextPartner() {
        updateStatus("Looking for partner...");
        isConnected = false;
        if (signalingClient != null) signalingClient.disconnect();
        if (peerConnection != null) peerConnection.close();
        resetConnectionAndFindNewPartner();
    }

    private void endCall() {
        if (signalingClient != null) signalingClient.disconnect();
        if (peerConnection != null) peerConnection.close();
        resetConnectionAndFindNewPartner();

        // ✅ restore audio mode
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        finish();
    }

    private void toggleCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
            isFrontCamera = !isFrontCamera;
        }
    }

    private void resetConnectionAndFindNewPartner() {
        hasOffered = false; // 🔥 reset để lần sau gọi lại
        initializeWebRTC();
    }

    private void toggleMicrophone() {
        if (localAudioTrack != null) {
            isAudioEnabled = !isAudioEnabled;
            localAudioTrack.setEnabled(isAudioEnabled);
            btnToggleMic.setImageResource(isAudioEnabled ?
                    R.drawable.ic_mic : R.drawable.ic_mic_off);
        }
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> tvStatus.setText(status));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                initializeWebRTC();
            } else {
                Toast.makeText(this, "Camera and microphone permissions are required!", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException e) { e.printStackTrace(); }
            videoCapturer.dispose();
        }
        if (localVideoTrack != null && localVideoView != null) {
            localVideoTrack.removeSink(localVideoView);
        }
        if (localVideoView != null) localVideoView.release();
        if (remoteVideoView != null) remoteVideoView.release();
        if (eglBase != null) eglBase.release();

        // ✅ restore audio mode
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        super.onDestroy();
    }
}
