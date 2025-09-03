package com.example.videochat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
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

    // State
    private boolean isVideoEnabled = true;
    private boolean isAudioEnabled = true;
    private boolean isFrontCamera = true;
    private boolean isConnected = false;

    // Signaling
    private SignalingClient signalingClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_chat);

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
        // Release video views if already initialized to prevent double init
        if (localVideoView != null) {
            localVideoView.release();
        }
        if (remoteVideoView != null) {
            remoteVideoView.release();
        }

        eglBase = EglBase.create();

        // Initialize PeerConnectionFactory
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        // Create PeerConnectionFactory
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        // Create video capturer
        createVideoCapturer();

        // Create video source and track
        localVideoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoCapturer.initialize(surfaceTextureHelper, this, localVideoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 640, 30);

        localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", localVideoSource);

        // Create audio source and track
        localAudioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", localAudioSource);

        // Setup local video view
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
        localVideoTrack.addSink(localVideoView);

        // Setup remote video view
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        remoteVideoView.setEnableHardwareScaler(true);
        // Create PeerConnection
        createPeerConnection();

        // Initialize signaling client
        initializeSignaling();
    }

    private void initializeSignaling() {
        signalingClient = new SignalingClient(new SignalingClient.SignalingCallback() {
            @Override
            public void onRemoteDescription(SessionDescription sessionDescription) {
                Log.d(TAG, "[SIGNALING] Got remote description: " + sessionDescription.type);
                // Đảm bảo luôn setRemoteDescription, và phía callee chỉ tạo answer khi nhận offer
                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        if (sessionDescription.type == SessionDescription.Type.OFFER) {
                            Log.d(TAG, "[SIGNALING] setRemoteDescription(OFFER) success, creating ANSWER");
                            peerConnection.createAnswer(new SimpleSdpObserver() {
                                @Override
                                public void onCreateSuccess(SessionDescription answerSdp) {
                                    Log.d(TAG, "[SIGNALING] Created ANSWER, setting local description and sending...");
                                    peerConnection.setLocalDescription(new SimpleSdpObserver(), answerSdp);
                                    signalingClient.sendAnswer(answerSdp);
                                }
                            }, new MediaConstraints());
                        } else if (sessionDescription.type == SessionDescription.Type.ANSWER) {
                            Log.d(TAG, "[SIGNALING] setRemoteDescription(ANSWER) success.");
                        }
                    }
                }, sessionDescription);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "[SIGNALING] Got remote ICE candidate");
                if (peerConnection != null) {
                    peerConnection.addIceCandidate(iceCandidate);
                }
            }

            @Override
            public void onPartnerFound(String partnerId) {
                String myId = signalingClient.getClientId();
                // Rõ ràng chỉ caller tạo OFFER, callee chờ OFFER
                boolean isCaller = myId.compareTo(partnerId) > 0;
                Log.d(TAG, "Partner found! I am " + (isCaller ? "CALLER" : "CALLEE") + ".");
                updateStatus("Partner found! Creating connection...");
                if (isCaller) {
                    new Handler().postDelayed(() -> {
                        if (peerConnection.signalingState() == PeerConnection.SignalingState.STABLE) {
                            createOffer();
                        }
                    }, 1000);
                }
                // callee thì chỉ chờ nhận offer
            }

            @Override
            public void onPartnerDisconnected() {
                runOnUiThread(() -> {
                    updateStatus("Partner disconnected");
                    Log.d(TAG, "[SIGNALING] Partner disconnected, closing peer and resetting...");
                    isConnected = false;
                    if (peerConnection != null) peerConnection.close();
                    // Giải phóng trạng thái hiện tại và chuẩn bị room mới để bắt cặp tiếp
                    resetConnectionAndFindNewPartner();
                });
            }
        });
    }

    private void createOffer() {
        if (peerConnection.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Cannot create offer, signaling state: " + peerConnection.signalingState());
            return;
        }

        Log.d(TAG, "Creating OFFER...");
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                signalingClient.sendOffer(sessionDescription);
            }

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "[SIGNALING] createOffer failed: " + error);
            }
        }, new MediaConstraints());
    }


    private static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
        }
    }

    private void createVideoCapturer() {
        videoCapturer = new Camera2Capturer(this, "0", new CameraVideoCapturer.CameraEventsHandler() {
            @Override
            public void onCameraError(String errorDescription) {
                Log.e(TAG, "Camera error: " + errorDescription);
            }

            @Override
            public void onCameraDisconnected() {
                Log.d(TAG, "Camera disconnected");
            }

            @Override
            public void onCameraFreezed(String errorDescription) {
                Log.e(TAG, "Camera freezed: " + errorDescription);
            }

            @Override
            public void onCameraOpening(String cameraName) {
                Log.d(TAG, "Camera opening: " + cameraName);
            }

            @Override
            public void onFirstFrameAvailable() {
                Log.d(TAG, "First frame available");
            }

            @Override
            public void onCameraClosed() {
                Log.d(TAG, "Camera closed");
            }
        });
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();

        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "[WEBRTC] Local ICE candidate: " + iceCandidate.sdp);
                if (signalingClient != null) {
                    signalingClient.sendIceCandidate(iceCandidate);
                }
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "[WEBRTC] onAddTrack: " + rtpReceiver.id());
                MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack && remoteVideoView != null) {
                    VideoTrack remoteVideoTrack = (VideoTrack) track;
                    remoteVideoTrack.addSink(remoteVideoView);
                    Log.d(TAG, "[WEBRTC] Remote video track added via onAddTrack!");
                    runOnUiThread(() -> {
                        updateStatus("Connected!");
                        isConnected = true;
                    });
                }
            }

            @Override
            public void onAddStream(MediaStream stream) {
                // Deprecated trong Unified Plan → để trống
                Log.d(TAG, "[WEBRTC] onAddStream (deprecated) ignored.");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                // Chỉ cần nếu bạn muốn dùng DataChannel (chat, tín hiệu custom)
                Log.d(TAG, "[WEBRTC] onDataChannel opened: " + dataChannel.label());
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.d(TAG, "[WEBRTC] Connection state: " + newState);
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "[WEBRTC] Signaling state: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "[WEBRTC] ICE connection state: " + iceConnectionState);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "[WEBRTC] ICE connection receiving change: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "[WEBRTC] ICE gathering state: " + iceGatheringState);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "[WEBRTC] ICE candidates removed: " + iceCandidates.length);
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "[WEBRTC] onRemoveStream");
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "[WEBRTC] onRenegotiationNeeded");
            }
        });

        // ✅ Add local tracks với streamId để Unified Plan hoạt động chính xác
        if (peerConnection != null) {
            List<String> streamIds = Collections.singletonList("local_stream");
            Log.d(TAG, "[WEBRTC] Adding local video and audio tracks...");
            peerConnection.addTrack(localVideoTrack, streamIds);
            peerConnection.addTrack(localAudioTrack, streamIds);
        }
    }


    private void findNextPartner() {
        updateStatus("Looking for partner...");
        isConnected = false;
        Log.d(TAG, "[CALL] Ending current connection and looking for next partner");
        if (signalingClient != null) {
            signalingClient.disconnect();
        }
        if (peerConnection != null) {
            peerConnection.close();
        }
        resetConnectionAndFindNewPartner();
    }

    private void endCall() {
        if (signalingClient != null) {
            signalingClient.disconnect();
        }
        if (peerConnection != null) {
            peerConnection.close();
        }
        // Khi end call, reset matching để chuẩn bị state mới nếu user muốn match tiếp
        resetConnectionAndFindNewPartner();
        // Hoặc nếu muốn đóng Activity hẳn: finish();
        finish();
    }

    private void toggleCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) videoCapturer;
            cameraVideoCapturer.switchCamera(null);
            isFrontCamera = !isFrontCamera;
        }
    }

    private void resetConnectionAndFindNewPartner() {
        // Tạo lại signaling client, peer connection & UI để vào phòng mới
        Log.d(TAG, "[FLOW] Resetting connection and starting new match");
        // Có thể cần giải phóng các track/nguồn cũ (video/audio)
        initializeWebRTC(); // sẽ khởi tạo lại signaling, peer,...
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
        if (Thread.currentThread() == getMainLooper().getThread()) {
            // Đã ở UI thread, cập nhật trực tiếp
            tvStatus.setText(status);
        } else {
            // Không ở UI thread, chuyển về UI thread
            runOnUiThread(() -> tvStatus.setText(status));
        }
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
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capture", e);
            }
            videoCapturer.dispose();
        }
        if (localVideoTrack != null && localVideoView != null) {
            localVideoTrack.removeSink(localVideoView);
        }
        if (localVideoView != null) {
            localVideoView.release();
        }
        if (remoteVideoView != null) {
            remoteVideoView.release();
        }
        if (eglBase != null) {
            eglBase.release();
        }
        super.onDestroy();
    }
}
