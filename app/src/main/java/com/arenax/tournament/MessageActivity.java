package com.arenax.tournament;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.arenax.tournament.data.FirebaseRepository;
import com.arenax.tournament.model.Tournament;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.bumptech.glide.Glide;
import java.util.*;

public class MessageActivity extends AppCompatActivity {
    private final List<Tournament> matches = new ArrayList<>();
    private Tournament selected;
    private LinearLayout matchList;
    private TextView selectedLabel;
    private EditText roomId, password, title, description, schedule;
    private Spinner target, mode;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_message);
        matchList=findViewById(R.id.message_match_list); selectedLabel=findViewById(R.id.message_selected_match); mode=findViewById(R.id.message_mode);
        roomId=findViewById(R.id.message_room_id); password=findViewById(R.id.message_password); title=findViewById(R.id.message_title); description=findViewById(R.id.message_description); schedule=findViewById(R.id.message_schedule); target=findViewById(R.id.message_target);
        schedule.setText("0");
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"MATCH-BASED NOTIFICATION", "ALL USERS IN-APP NOTIFICATION"}));
        target.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"MATCH JOINED USERS", "MATCH NOT JOINED USERS", "ALL USERS IN THIS MATCH"}));
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onNothingSelected(android.widget.AdapterView<?> p) {} public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) { findViewById(R.id.message_match_section).setVisibility(position == 0 ? View.VISIBLE : View.GONE); }});
        findViewById(R.id.message_suggestion_room).setOnClickListener(v -> { title.setText("Room Details Released"); description.setText("Room ID: " + roomId.getText().toString().trim() + "\nPassword: " + password.getText().toString().trim()); });
        findViewById(R.id.message_suggestion_reminder).setOnClickListener(v -> { title.setText("Match Reminder"); description.setText("Your selected match is starting soon. Please be ready."); });
        findViewById(R.id.message_send).setOnClickListener(v -> saveMessage());
        loadMatches();
    }
    private void loadMatches() {
        FirebaseRepository.tournaments().get().addOnSuccessListener(snapshot -> {
            matchList.removeAllViews(); matches.clear();
            for (DataSnapshot child : snapshot.getChildren()) {
                @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)child.getValue(); Tournament t=Tournament.fromMap(child.getKey(), map); if(t==null) continue; matches.add(t);
                View row = getLayoutInflater().inflate(R.layout.item_message_match, matchList, false);
                ImageView banner = row.findViewById(R.id.message_match_banner);
                TextView rowTitle = row.findViewById(R.id.message_match_title);
                TextView meta = row.findViewById(R.id.message_match_meta);
                TextView slots = row.findViewById(R.id.message_match_slots);
                rowTitle.setText(t.getTitle());
                meta.setText(t.getDate()+" • "+t.getTime()+"\n"+t.getMatchType()+" • "+t.getMap());
                slots.setText("Joined "+t.getJoinedSlots()+" / "+t.getTotalSlots());
                if (t.getBannerUrl() != null && !t.getBannerUrl().trim().isEmpty()) Glide.with(this).load(t.getBannerUrl()).centerCrop().into(banner);
                row.setOnClickListener(v -> selectMatch(t)); matchList.addView(row);
            }
            if(matches.isEmpty()){ TextView empty=new TextView(this); empty.setText("No matches found"); empty.setTextColor(getColor(R.color.text_secondary)); matchList.addView(empty); }
        }).addOnFailureListener(e -> toast("Could not load matches"));
    }
    private void selectMatch(Tournament t) { selected=t; selectedLabel.setText("Selected: "+t.getTitle()+"\nJoined: "+t.getJoinedSlots()+" / "+t.getTotalSlots()); roomId.setText(t.getRoomId()); password.setText(t.getRoomPassword()); }
    private void saveMessage() {
        String heading=title.getText().toString().trim(), body=description.getText().toString().trim(); boolean allUsersMode = mode.getSelectedItemPosition() == 1; if(!allUsersMode && selected==null){toast("Select a match first");return;} if(heading.isEmpty()||body.isEmpty()){toast("Enter title and description");return;}
        View sendButton = findViewById(R.id.message_send);
        sendButton.setEnabled(false);
        ((com.google.android.material.button.MaterialButton) sendButton).setText("SENDING...");
        long parsedSchedule=0; try{parsedSchedule=Long.parseLong(schedule.getText().toString().trim());}catch(Exception ignored){}
        final long scheduled = parsedSchedule;
        final String roomValue = roomId.getText().toString().trim();
        final String passwordValue = password.getText().toString().trim();
        String targetType = allUsersMode ? "ALL_USERS" : (target.getSelectedItemPosition()==0 ? "MATCH_JOINED" : target.getSelectedItemPosition()==1 ? "MATCH_NOT_JOINED" : "MATCH_ALL_USERS");
        DatabaseReference users=FirebaseRepository.users(); users.get().addOnSuccessListener(all -> {
            if (allUsersMode) { saveTargetedMessage(heading, body, targetType, null, all, new HashMap<>(), roomValue, passwordValue, scheduled, sendButton); return; }
            FirebaseRepository.tournamentParticipants(selected.getId()).get().addOnSuccessListener(participants -> {
                Map<String,Object> ids=new HashMap<>(); for(DataSnapshot user:all.getChildren()){boolean joined=participants.child(user.getKey()).exists(); if(("MATCH_JOINED".equals(targetType)&&joined)||("MATCH_NOT_JOINED".equals(targetType)&&!joined)||"MATCH_ALL_USERS".equals(targetType)) ids.put(user.getKey(),true);}
                saveTargetedMessage(heading, body, targetType, selected.getId(), all, ids, roomValue, passwordValue, scheduled, sendButton);
            }).addOnFailureListener(e->{sendButton.setEnabled(true); ((com.google.android.material.button.MaterialButton) sendButton).setText("SEND MESSAGE"); toast("Failed to load match participants: "+e.getMessage());});
        }).addOnFailureListener(e->{sendButton.setEnabled(true); ((com.google.android.material.button.MaterialButton) sendButton).setText("SEND MESSAGE"); toast("Failed to load users: "+e.getMessage());});
    }
    private void saveTargetedMessage(String heading, String body, String targetType, String matchId, DataSnapshot all, Map<String,Object> ids, String roomValue, String passwordValue, long scheduled, View sendButton) { if ("ALL_USERS".equals(targetType)) for (DataSnapshot user: all.getChildren()) ids.put(user.getKey(), true); String id=FirebaseRepository.notifications().push().getKey(); Map<String,Object> msg=new HashMap<>(); msg.put("title",heading); msg.put("message",body); msg.put("targetType",targetType); if(matchId!=null) msg.put("matchId",matchId); msg.put("targetUserIds",ids); msg.put("roomId",roomValue); msg.put("roomPassword",passwordValue); msg.put("scheduledAt",scheduled); msg.put("createdAt",System.currentTimeMillis()); msg.put("status",scheduled>System.currentTimeMillis()?"SCHEDULED":"QUEUED"); FirebaseRepository.notifications().child(id==null?"message":id).setValue(msg).addOnSuccessListener(v->{sendButton.setEnabled(true); ((com.google.android.material.button.MaterialButton) sendButton).setText("SEND MESSAGE"); toast("Success: "+ids.size()+" users targeted"); title.setText("");description.setText("");}).addOnFailureListener(e->{sendButton.setEnabled(true); ((com.google.android.material.button.MaterialButton) sendButton).setText("SEND MESSAGE"); toast("Failed to save message: "+e.getMessage());}); }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
