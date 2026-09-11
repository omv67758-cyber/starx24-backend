package com.arenax.tournament;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import com.arenax.tournament.data.FirebaseRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import okhttp3.*;
import org.json.JSONObject;
import java.util.*;

/** Shared implementation; each product flavor exposes a different concrete launcher. */
public abstract class RoleAdminActivity extends Activity {
    protected abstract String role();
    protected abstract String title();
    private LinearLayout body;
    private TextView state;
    private final int RED = Color.rgb(194, 32, 48);
    private final int BLACK = Color.rgb(13, 14, 17);
    private final int CARD = Color.rgb(30, 31, 37);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AdminLoginActivity.class)); finish(); return;
        }
        buildShell(); loadRoleData();
    }
    private void buildShell() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BLACK);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(20, 22, 20, 18);
        TextView brand = text("STARX24  /  " + title(), 20, Color.WHITE, true); bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView logout = text("LOG OUT", 11, Color.LTGRAY, true); logout.setOnClickListener(v -> { FirebaseAuth.getInstance().signOut(); startActivity(new Intent(this, AdminLoginActivity.class)); finish(); }); bar.addView(logout);
        root.addView(bar);
        state = text("Loading live data…", 13, Color.LTGRAY, false); state.setPadding(20, 0, 20, 12); root.addView(state);
        ScrollView scroll = new ScrollView(this); body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(16, 8, 16, 28); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    protected void loadRoleData() {
        body.removeAllViews(); body.addView(card("SECURITY", "Role: " + role() + "\nAll write operations are checked again by Firebase rules."));
        if ("PAYMENT_ADMIN".equals(role())) renderPayments(); else if ("MATCH_ADMIN".equals(role())) renderMatches(); else renderMaster();
    }
    private void renderPayments() {
        FirebaseRepository.withdrawals().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) { state.setText("Live payment queue • server timestamps"); int pending=0, overdue=0, paid=0; long now=System.currentTimeMillis();
                for (DataSnapshot w:s.getChildren()) { String st=w.child("status").getValue(String.class); long requested=num(w,"requestedAt","createdAt"); if("PAID".equals(st)) paid++; else { pending++; if(now-requested>=86400000L) overdue++; } }
                body.addView(card("PAYMENT QUEUE", "Pending: " + pending + "   Over 24H: " + overdue + "   Paid: " + paid));
                if(!s.exists()) body.addView(empty("No withdrawal requests", "The queue is currently empty."));
                for(DataSnapshot w:s.getChildren()) paymentRow(w);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { error(e); }
        });
    }
    private void paymentRow(DataSnapshot w) { String id=w.getKey(); String status=w.child("status").getValue(String.class); long age=System.currentTimeMillis()-num(w,"requestedAt","createdAt"); boolean late=age>=86400000L; String chip="PAID".equals(status)?"✓  PAID":"PENDING".equals(status)&&(late)?"OVERDUE / 24H+":"PENDING"; int color="PAID".equals(status)?Color.rgb(42,190,112):(late?RED:Color.rgb(224,177,40));
        LinearLayout row=card("Withdrawal " + id, "User: " + value(w,"userId","unknown") + "\nAmount: ₹" + value(w,"amount","0") + "\n" + value(w,"paymentMethod","method not supplied")); TextView tag=text(chip,12,color,true); row.addView(tag); if("PENDING".equals(status)){ Button paid=button("MARK AS PAID"); paid.setOnClickListener(v -> confirmPaid(id,w.child("userId").getValue(String.class))); row.addView(paid); } body.addView(row); }
    private void confirmPaid(String id,String userId){ new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Confirm payment").setMessage("Are you sure this payment has been completed?").setPositiveButton("MARK AS PAID",(d,w)->FirebaseRepository.markWithdrawalPaid(id,"Processed by payment admin").addOnSuccessListener(x->{ if(userId!=null) FirebaseRepository.addUserNotification(userId,"Withdrawal Completed","Your withdrawal has been successfully processed.","WITHDRAWAL"); Toast.makeText(this,"Payment updated and audited",Toast.LENGTH_SHORT).show(); loadRoleData(); }).addOnFailureListener(x->Toast.makeText(this,"Payment was not updated; it may already be handled.",Toast.LENGTH_LONG).show())).setNegativeButton("CANCEL",null).show(); }
    private void renderMatches(){ FirebaseRepository.matches().addListenerForSingleValueEvent(new ValueEventListener(){ public void onDataChange(@NonNull DataSnapshot s){ state.setText("Live match content"); int active=0; for(DataSnapshot m:s.getChildren()){String st=value(m,"status",""); if(!"COMPLETED".equals(st))active++;} body.addView(card("MATCH OPERATIONS","Total matches: "+s.getChildrenCount()+"   Active: "+active)); body.addView(buttonRow("ADD MATCH",v->showMatchDialog(null))); for(DataSnapshot m:s.getChildren()) matchRow(m); } public void onCancelled(@NonNull DatabaseError e){error(e);} }); FirebaseRepository.banners().addListenerForSingleValueEvent(new ValueEventListener(){public void onDataChange(@NonNull DataSnapshot s){body.addView(card("BANNERS","Enabled content: "+s.getChildrenCount()+"\nUse the existing banner editor in the user-facing admin workspace for detailed image fields."));}public void onCancelled(@NonNull DatabaseError e){}}); }
    private void matchRow(DataSnapshot m){ LinearLayout r=card(value(m,"title",value(m,"name",m.getKey())),value(m,"categoryId",value(m,"mode","Uncategorised"))+"\nStatus: "+value(m,"status","WAITING")+"\nRoom credentials remain hidden until release."); r.addView(buttonRow("EDIT / RELEASE ROOM",v->showMatchDialog(m))); body.addView(r); }
    private void showMatchDialog(DataSnapshot m){ LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL); EditText title=new EditText(this);title.setHint("Match title");title.setText(m==null?"":value(m,"title",value(m,"name",""))); EditText category=new EditText(this);category.setHint("Category");category.setText(m==null?"":value(m,"categoryId","")); EditText room=new EditText(this);room.setHint("Room ID (only released on action)"); box.addView(title);box.addView(category);box.addView(room); new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(m==null?"Add match":"Edit match").setView(box).setPositiveButton("SAVE",(d,w)->{Map<String,Object> data=new HashMap<>();data.put("title",title.getText().toString().trim());data.put("categoryId",category.getText().toString().trim());data.put("status","WAITING");data.put("published",true);data.put("updatedAt",ServerValue.TIMESTAMP);String id=m==null?FirebaseRepository.matches().push().getKey():m.getKey();FirebaseRepository.matches().child(id).updateChildren(data).addOnSuccessListener(x->{FirebaseRepository.logActivity(m==null?"MATCH_CREATED":"MATCH_EDITED",id,title.getText().toString());loadRoleData();});}).setNegativeButton("CANCEL",null).show(); }
    private void renderMaster(){ FirebaseRepository.adminUsers().addListenerForSingleValueEvent(new ValueEventListener(){public void onDataChange(@NonNull DataSnapshot s){state.setText("Live system control");body.addView(card("ADMIN ACCOUNTS","Payment admins: " + count(s,"PAYMENT_ADMIN") + "\nMatch admins: " + count(s,"MATCH_ADMIN") + "\nDisabled/revoked accounts must be rejected by backend rules.")); Button key=button("GENERATE ADMIN KEY / ACCOUNT"); key.setOnClickListener(v->showProvisionDialog()); body.addView(key); body.addView(card("CONTROL POLICY","Only Master Control can provision Payment or Match admin credentials. The backend creates the Firebase account and returns a one-time password."));}public void onCancelled(@NonNull DatabaseError e){error(e);}}); FirebaseRepository.activityLogs().limitToLast(20).addListenerForSingleValueEvent(new ValueEventListener(){public void onDataChange(@NonNull DataSnapshot s){body.addView(card("RECENT AUDIT ACTIVITY",s.exists()?s.getChildrenCount()+" events available":"No activity recorded yet."));}public void onCancelled(@NonNull DatabaseError e){}}); }
    private void showProvisionDialog(){ LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); EditText email=new EditText(this); email.setHint("Admin email"); EditText display=new EditText(this); display.setHint("Display name"); Spinner role=new Spinner(this); role.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"PAYMENT_ADMIN","MATCH_ADMIN"})); box.addView(email); box.addView(display); box.addView(role); new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Generate admin credentials").setMessage("Master Control only. Password is shown once.").setView(box).setPositiveButton("GENERATE",(d,w)->provisionAdmin(email.getText().toString().trim(),display.getText().toString().trim(),String.valueOf(role.getSelectedItem()))).setNegativeButton("CANCEL",null).show(); }
    private void provisionAdmin(String email,String display,String role){ FirebaseAuth.getInstance().getCurrentUser().getIdToken(true).addOnSuccessListener(token->{ try { JSONObject payload=new JSONObject(); payload.put("email",email); payload.put("displayName",display); payload.put("role",role); RequestBody body=RequestBody.create(payload.toString(),MediaType.parse("application/json")); Request request=new Request.Builder().url(BuildConfig.STARX_API_BASE_URL+"/admin/provision").header("Authorization","Bearer "+token.getToken()).post(body).build(); new OkHttpClient().newCall(request).enqueue(new Callback(){public void onFailure(Call c,java.io.IOException e){runOnUiThread(()->Toast.makeText(RoleAdminActivity.this,"Provision failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}public void onResponse(Call c,Response r)throws java.io.IOException{String text=r.body()==null?"":r.body().string();runOnUiThread(()->{try{JSONObject out=new JSONObject(text);Toast.makeText(RoleAdminActivity.this,r.isSuccessful()?"Generated: "+out.optString("email")+" / "+out.optString("temporaryPassword"):out.optString("error","Provision failed"),Toast.LENGTH_LONG).show();if(r.isSuccessful())loadRoleData();}catch(Exception e){Toast.makeText(RoleAdminActivity.this,"Invalid backend response",Toast.LENGTH_LONG).show();}});}});}catch(Exception e){Toast.makeText(this,"Could not create request",Toast.LENGTH_LONG).show();}}).addOnFailureListener(e->Toast.makeText(this,"Session expired. Login again.",Toast.LENGTH_LONG).show()); }
    private int count(DataSnapshot s,String role){int n=0;for(DataSnapshot x:s.getChildren())if(role.equals(value(x,"role","")))n++;return n;}
    private long num(DataSnapshot s,String a,String b){Long n=s.child(a).getValue(Long.class);if(n==null)n=s.child(b).getValue(Long.class);return n==null?System.currentTimeMillis():n;}
    private String value(DataSnapshot s,String k,String d){String x=s.child(k).getValue(String.class);return x==null||x.isEmpty()?d:x;}
    private LinearLayout card(String h,String d){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(16,14,16,14);c.setBackgroundColor(CARD);c.setLayoutParams(new LinearLayout.LayoutParams(-1,-2)); TextView a=text(h,15,Color.WHITE,true),b=text(d,13,Color.LTGRAY,false);c.addView(a);c.addView(b);return c;}
    private TextView empty(String h,String d){return text(h+"\n"+d,15,Color.LTGRAY,false);}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(null,bold?1:0);t.setPadding(0,3,0,7);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundColor(RED);return b;}
    private Button buttonRow(String s,View.OnClickListener l){Button b=button(s);b.setOnClickListener(l);return b;}
    private void error(DatabaseError e){state.setText("Backend error: "+e.getMessage()+"  • Retry");}
}
