package com.example.photoviewer;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Device-level integration checks. Screenshots come directly from UiAutomation. */
public class EvidenceRunner extends Instrumentation {
    private Bundle args;
    private MainActivity activity;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            String server=args.getString("server",MainActivity.LOCAL);
            String mode=args.getString("mode","local");
            android.content.SharedPreferences prefs=context.getSharedPreferences("photo_viewer",0);
            prefs.edit().putString("server",server).commit();
            String token=args.getString("token","");
            if(!token.isEmpty())prefs.edit().putString("token-"+hash(server),token).commit();
            activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pause();
            capture(mode+"-initial");
            if(mode.equals("audit")) {
                audit(context);
            } else if(mode.equals("settings")) {
                runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
                pause();
                capture("settings");
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            } else if(mode.equals("cache")) {
                check(status().contains("보관한 사진"),"Cache restored after process restart");
                capture("offline-cache");
            } else {
                runOnMainSync(()->activity.findViewById(R.id.sync).performClick());
                waitSync();
                check(status().contains("동기화 완료"),"Server synchronization: "+activity.lastError);
                pause();capture(mode+"-synced");
                if(mode.equals("local")) {
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText("첫 번째"));
                    capture("search");
                    runOnMainSync(()->((EditText)activity.findViewById(R.id.search)).setText(""));
                    runOnMainSync(()->((androidx.recyclerview.widget.RecyclerView)activity.findViewById(R.id.posts)).getChildAt(0).performClick());
                    pause();capture("detail");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
                    pause();capture("settings");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
                    pause();
                    ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"photoviewer-test.png");values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                    Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                    try(InputStream in=getContext().getAssets().open("cat_what.png");OutputStream out=context.getContentResolver().openOutputStream(uri)) {in.transferTo(out);}
                    // Inject the Android picker result; upload itself uses the production multipart path.
                    runOnMainSync(()->{
                        activity.onActivityResult(100,Activity.RESULT_OK,new Intent().setData(uri));
                        try {
                            ((EditText)field("titleInput")).setText("Android에서 올린 고양이");
                            ((EditText)field("bodyInput")).setText("뭐여 고양이 사진을 선택하고 multipart POST로 업로드했습니다.");
                        }catch(Exception e){throw new RuntimeException(e);}
                    });
                    pause();capture("upload-form");
                    runOnMainSync(()->{try{((AlertDialog)field("editor")).getButton(-2).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                    waitForIdleSync();
                    runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
                    check(((EditText)field("titleInput")).getText().toString().contains("Android"),"Draft restored");
                    capture("draft-restored");
                    runOnMainSync(()->{try{((AlertDialog)field("editor")).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                    waitSync();pause();
                    check(status().contains("동기화 완료"),"Multipart upload and refresh completed");
                    check(((EditText)field("titleInput")).getText().length()==0,"Upload cleared draft");
                    capture("upload-complete");
                }
            }
            result.putString("stream","\nPASS: "+mode+" device integration and genuine screenshots\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","\nFAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
    private EditText input(AlertDialog dialog,String hint) {
        return findInput(dialog.getWindow().getDecorView(),hint);
    }
    private EditText findInput(View view,String hint) {
        if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint()))return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=findInput(((ViewGroup)view).getChildAt(i),hint);if(found!=null)return found;
        }
        return null;
    }
    private void recreate() throws Exception {
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        MainActivity old=activity;
        runOnMainSync(old::recreate);
        activity=(MainActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
        check(activity!=null && activity!=old,"Activity recreated");waitForIdleSync();
    }
    private void audit(Context context) throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("photo_viewer",0);
        Map<String,?> previous=new HashMap<>(prefs.getAll());
        Uri sample=null;
        try {
            prefs.edit().putString("token-"+hash(MainActivity.LOCAL),"audit-local-token")
                .putString("token-"+hash(MainActivity.CLOUD),"audit-cloud-token").commit();
            runOnMainSync(()->activity.findViewById(R.id.settings).performClick());
            AlertDialog settings=(AlertDialog)field("settingsDialog");
            EditText url=input(settings,"서버 URL"),token=input(settings,"API 토큰 (글 작성 시 필요)");
            runOnMainSync(()->url.setText(MainActivity.CLOUD));
            check(token.getText().toString().equals("audit-cloud-token"),"Cloud token loaded instead of local token");
            runOnMainSync(()->url.setText("https://unknown.example"));
            check(token.getText().length()==0,"Unknown server does not receive another server's token");
            runOnMainSync(()->url.setText(MainActivity.LOCAL));
            check(token.getText().toString().equals("audit-local-token"),"Local token restored");
            runOnMainSync(()->{token.setText("");settings.getButton(-1).performClick();});
            waitForIdleSync();
            check(prefs.getString("token-"+hash(MainActivity.LOCAL),"missing").isEmpty(),"Token can be cleared");
            check(prefs.getString("token-"+hash(MainActivity.CLOUD),"").equals("audit-cloud-token"),"Cloud token unchanged");
            runOnMainSync(()->activity.findViewById(R.id.upload).performClick());
            EditText title=(EditText)field("titleInput"),body=(EditText)field("bodyInput");
            runOnMainSync(()->{title.setText("회전 중인 초안");body.setText("화면 회전 후에도 유지");});
            recreate();
            check(((EditText)field("titleInput")).getText().toString().equals("회전 중인 초안"),"Draft title survived recreation");
            check(((EditText)field("bodyInput")).getText().toString().equals("화면 회전 후에도 유지"),"Draft body survived recreation");
            capture("audit-draft-recreated");

            ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"photoviewer-audit.png");values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            sample=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            try(InputStream in=getContext().getAssets().open("cat_what.png");OutputStream out=context.getContentResolver().openOutputStream(sample)){in.transferTo(out);}
            // A local delayed HTTP response deterministically reproduces completion after recreation.
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                server.setSoTimeout(15000);
                CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
                Thread peer=new Thread(()->{
                    try(Socket socket=server.accept()) {
                        socket.setSoTimeout(15000);InputStream in=socket.getInputStream();
                        ByteArrayOutputStream header=new ByteArrayOutputStream();int tail=0,b;
                        while((b=in.read())!=-1){header.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
                        int length=0;for(String line:header.toString("UTF-8").split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.split(":",2)[1].trim());
                        while(length>0){int n=in.read(new byte[Math.min(length,8192)]);if(n<0)throw new EOFException();length-=n;}
                        received.countDown();release.await(15,TimeUnit.SECONDS);
                        socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
                    }catch(Exception ignored){ /* The recreated activity may cancel its old connection. */ }
                });peer.setDaemon(true);peer.start();
                String endpoint="http://127.0.0.1:"+server.getLocalPort();
                prefs.edit().putString("token-"+hash(endpoint),"audit-only").commit();
                Field base=MainActivity.class.getDeclaredField("base");base.setAccessible(true);base.set(activity,endpoint);
                final Uri image=sample;
                runOnMainSync(()->{activity.onActivityResult(100,Activity.RESULT_OK,new Intent().setData(image));try{((AlertDialog)field("editor")).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
                check(received.await(15,TimeUnit.SECONDS),"Multipart request reached delayed test server");
                recreate();release.countDown();peer.join(3000);pause();
                check(!activity.isDestroyed(),"Late upload completion did not crash the recreated activity");
            }
        } finally {
            runOnMainSync(()->{try{AlertDialog dialog=(AlertDialog)field("editor");if(dialog!=null)dialog.dismiss();}catch(Exception e){throw new RuntimeException(e);}});
            if(sample!=null)context.getContentResolver().delete(sample,null,null);
            android.content.SharedPreferences.Editor restore=prefs.edit().clear();
            for(Map.Entry<String,?> entry:previous.entrySet()) {
                Object value=entry.getValue();if(value instanceof String)restore.putString(entry.getKey(),(String)value);
            }
            restore.commit();
        }
    }
    private Object field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private String status(){return ((TextView)activity.findViewById(R.id.status)).getText().toString();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void pause(){waitForIdleSync();SystemClock.sleep(1800);}
    private void waitSync(){long until=SystemClock.elapsedRealtime()+45000;while(!activity.idle && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);waitForIdleSync();check(activity.idle,"Network request timed out");}
    private void capture(String name)throws Exception {
        waitForIdleSync();SystemClock.sleep(500);
        File folder=new File(getTargetContext().getExternalFilesDir(null),"evidence");folder.mkdirs();
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private static String hash(String text)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b));return out.toString();}
}
