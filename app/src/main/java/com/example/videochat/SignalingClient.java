package com.example.videochat;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private static final String ROOMS_KEY = "videochat_rooms";
    private DatabaseReference roomsRef;
    private String clientId;
    private String roomId;
    private boolean isCaller;
    private SignalingCallback callback;

    public interface SignalingCallback {
        void onRemoteDescription(SessionDescription sessionDescription);
        void onIceCandidate(IceCandidate iceCandidate);
        void onPartnerFound(String partnerId);
        void onPartnerDisconnected();
    }

    public SignalingClient(SignalingCallback callback) {
        this.callback = callback;
        this.clientId = UUID.randomUUID().toString();
        this.roomsRef = FirebaseDatabase.getInstance().getReference(ROOMS_KEY);
        findOrCreateRoom();
    }

    private void findOrCreateRoom() {
        roomsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean joined = false;
                for (DataSnapshot roomSnap : snapshot.getChildren()) {
                    String callerId = roomSnap.child("callerId").getValue(String.class);
                    String calleeId = roomSnap.child("calleeId").getValue(String.class);
                    if (callerId != null && calleeId == null) {
                        // Join room as callee
                        roomId = roomSnap.getKey();
                        roomsRef.child(roomId).child("calleeId").setValue(clientId);
                        isCaller = false;
                        listenForSignals();
                        callback.onPartnerFound(callerId);
                        joined = true;
                        Log.d(TAG, "Joined room as callee: " + roomId);
                        break;
                    }
                }
                if (!joined) {
                    // Create new room as caller
                    roomId = roomsRef.push().getKey();
                    Map<String, Object> roomData = new HashMap<>();
                    roomData.put("callerId", clientId);
                    roomsRef.child(roomId).setValue(roomData);
                    isCaller = true;
                    listenForSignals();
                    Log.d(TAG, "Created room as caller: " + roomId);
                    // Wait for partner to join
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase error: " + error.getMessage());
            }
        });
    }

    private void listenForSignals() {
        DatabaseReference signalRef = roomsRef.child(roomId);
        // Offer/Answer
        signalRef.child(isCaller ? "answer" : "offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    String type = snapshot.child("type").getValue(String.class);
                    if (sdp != null && type != null) {
                        SessionDescription.Type sdpType = SessionDescription.Type.fromCanonicalForm(type);
                        SessionDescription sessionDescription = new SessionDescription(sdpType, sdp);
                        callback.onRemoteDescription(sessionDescription);
                        Log.d(TAG, "Received " + type + ": " + sdp);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        });
        // ICE candidates
        signalRef.child(isCaller ? "calleeCandidates" : "callerCandidates").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot candSnap : snapshot.getChildren()) {
                    String sdp = candSnap.child("sdp").getValue(String.class);
                    String sdpMid = candSnap.child("sdpMid").getValue(String.class);
                    Long sdpMLineIndexLong = candSnap.child("sdpMLineIndex").getValue(Long.class);
                    if (sdp != null && sdpMid != null && sdpMLineIndexLong != null) {
                        int sdpMLineIndex = sdpMLineIndexLong.intValue();
                        IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                        callback.onIceCandidate(candidate);
                        Log.d(TAG, "Received ICE candidate: " + sdp);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        });
    }

    public void sendOffer(SessionDescription offer) {
        sendSessionDescription("offer", offer);
        Log.d(TAG, "Sent offer: " + offer.description);
    }

    public void sendAnswer(SessionDescription answer) {
        sendSessionDescription("answer", answer);
        Log.d(TAG, "Sent answer: " + answer.description);
    }

    private void sendSessionDescription(String key, SessionDescription sessionDescription) {
        DatabaseReference signalRef = roomsRef.child(roomId).child(key);
        Map<String, Object> data = new HashMap<>();
        data.put("sdp", sessionDescription.description);
        data.put("type", sessionDescription.type.canonicalForm());
        signalRef.setValue(data);
    }

    public void sendIceCandidate(IceCandidate iceCandidate) {
        String candidatesKey = isCaller ? "callerCandidates" : "calleeCandidates";
        DatabaseReference candidatesRef = roomsRef.child(roomId).child(candidatesKey).push();
        Map<String, Object> data = new HashMap<>();
        data.put("sdp", iceCandidate.sdp);
        data.put("sdpMid", iceCandidate.sdpMid);
        data.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        candidatesRef.setValue(data);
        Log.d(TAG, "Sent ICE candidate: " + iceCandidate.sdp);
    }

    public void disconnect() {
        if (roomId != null) {
            roomsRef.child(roomId).removeValue();
            Log.d(TAG, "Room removed: " + roomId);
            callback.onPartnerDisconnected();
        }
    }

    public String getClientId() {
        return clientId;
    }
}
