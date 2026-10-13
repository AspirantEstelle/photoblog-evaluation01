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
            if(mode.equals("cache")) {
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
                    try(InputStream in=getContext().getAssets().open("pig.png");OutputStream out=context.getContentResolver().openOutputStream(uri)) {in.transferTo(out);}
                    // Inject the Android picker result; upload itself uses the production multipart path.
                    runOnMainSync(()->{
                        activity.onActivityResult(100,Activity.RESULT_OK,new Intent().setData(uri));
                        try {
                            ((EditText)field("titleInput")).setText("Android에서 올린 사진");
                            ((EditText)field("bodyInput")).setText("제목과 본문을 입력하고 사진을 골라 multipart POST로 업로드했습니다.");
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
