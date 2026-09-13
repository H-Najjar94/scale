package com.hookscale.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String DEVICE_NAME = "HookScale-ESP32";
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BLE = 42;
    private static final double CAPACITY_KG = 15000.0;
    private static final int CALIBRATION_VERSION = 3;
    private static final String TAG = "FNC_SCALE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Double> recentKg = new ArrayDeque<>();
    private final WeightFilter rawFilter = new WeightFilter();
    private SharedPreferences prefs;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;
    private long lastPacketMs;
    private long raw;
    private double zeroRaw;
    private double zeroSpread;
    private boolean hasZero;
    private double countsPerKg;
    private double tareKg;
    private int calibrationStage;
    private double firstZeroRaw, firstZeroSpread, secondZeroRaw, secondZeroSpread;
    private double firstLoadRaw, firstLoadSpread, calibrationKg;
    private boolean showNet = true;
    private boolean tonnes;

    private TextView status, mode, unitLabel, stability, calibration, calibrationInstructions;
    private DotMatrixView value;
    private Button connectButton, tareButton, zeroButton, displayZeroButton, grossNetButton, unitButton, calibrateButton;
    private EditText knownWeight;
    private FrameLayout pages;
    private ScrollView scalePage, calibrationPage;
    private TextView scaleTab, calibrationTab;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("scale", MODE_PRIVATE);
        if (prefs.getInt("calibrationVersion", 0) == CALIBRATION_VERSION) {
            zeroRaw = Double.longBitsToDouble(prefs.getLong("zeroRawV2", 0));
            zeroSpread = Double.longBitsToDouble(prefs.getLong("zeroSpread", 0));
            hasZero = prefs.getBoolean("hasZero", false);
            countsPerKg = Double.longBitsToDouble(prefs.getLong("countsPerKg", 0));
            if (!Double.isFinite(zeroRaw) || !Double.isFinite(zeroSpread) ||
                    !Double.isFinite(countsPerKg)) {
                zeroRaw = zeroSpread = countsPerKg = 0; hasZero = false;
            }
        }
        tonnes = prefs.getBoolean("tonnes", false);
        buildUi();
        requestPermissionsAndScan();
        handler.post(connectionWatchdog);
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(8), dp(8), dp(8), dp(8)); return t;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setLetterSpacing(.02f); b.setBackground(background(Color.rgb(35,46,59), 18));
        b.setMinHeight(dp(52)); b.setStateListAnimator(null); return b;
    }
    private void add(LinearLayout p, View v) { p.addView(v, new LinearLayout.LayoutParams(-1, -2)); }
    private void addRow(LinearLayout p, View a, View b) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(54),1); left.setMargins(0,dp(6),dp(7),dp(6));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(54),1); right.setMargins(dp(7),dp(6),0,dp(6));
        row.addView(a,left); row.addView(b,right); p.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        final int bg = Color.rgb(8,12,18), card = Color.rgb(18,25,34), orange = Color.rgb(255,166,40), muted = Color.rgb(148,162,180);
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL); header.setPadding(dp(20),dp(18),dp(20),dp(8));
        LinearLayout brand=new LinearLayout(this); brand.setOrientation(LinearLayout.HORIZONTAL); brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.fnc_logo); logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE); brand.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView title = text("FNC-SCALE", 21, Color.WHITE); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); title.setLetterSpacing(.08f); LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,dp(46),1); titleLp.setMargins(dp(10),0,0,0); brand.addView(title,titleLp); add(header,brand);
        status = text("Starting Bluetooth…", 14, orange); status.setGravity(Gravity.START); status.setPadding(dp(8),0,dp(8),dp(4)); add(header,status); add(shell,header);

        pages = new FrameLayout(this); shell.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        scalePage = page(); calibrationPage = page(); pages.addView(scalePage); pages.addView(calibrationPage); calibrationPage.setVisibility(View.GONE);

        LinearLayout main = pageBody(scalePage);
        LinearLayout weightCard = new LinearLayout(this); weightCard.setOrientation(LinearLayout.VERTICAL); weightCard.setGravity(Gravity.CENTER); weightCard.setPadding(dp(14),dp(18),dp(14),dp(16)); weightCard.setBackground(background(card,28));
        mode = text("NET", 13, muted); mode.setTypeface(Typeface.DEFAULT,Typeface.BOLD); mode.setLetterSpacing(.12f); add(weightCard,mode);
        value = new DotMatrixView(this); value.setValue("----"); weightCard.addView(value,new LinearLayout.LayoutParams(-1,dp(118)));
        unitLabel=text("SETUP NEEDED",13,orange); unitLabel.setTypeface(Typeface.DEFAULT,Typeface.BOLD); unitLabel.setLetterSpacing(.10f); add(weightCard,unitLabel);
        stability = text("WAITING", 14, muted); stability.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(weightCard,stability);
        LinearLayout.LayoutParams weightLp=new LinearLayout.LayoutParams(-1,-2); weightLp.setMargins(0,dp(8),0,dp(16)); main.addView(weightCard,weightLp);

        connectButton = button("CONNECT"); connectButton.setOnClickListener(v -> requestPermissionsAndScan());
        tareButton = button("TARE"); tareButton.setOnClickListener(v -> setTare());
        displayZeroButton = button("ZERO"); displayZeroButton.setOnClickListener(v -> setTare());
        grossNetButton = button("GROSS"); grossNetButton.setOnClickListener(v -> { showNet=!showNet; updateDisplay(); });
        unitButton = button(tonnes ? "KG" : "TONNES"); unitButton.setOnClickListener(v -> { tonnes=!tonnes; prefs.edit().putBoolean("tonnes",tonnes).apply(); updateDisplay(); });
        addRow(main,tareButton,displayZeroButton); addRow(main,grossNetButton,unitButton);
        LinearLayout.LayoutParams connectLp=new LinearLayout.LayoutParams(-1,dp(54)); connectLp.setMargins(0,dp(10),0,dp(8)); main.addView(connectButton,connectLp);

        LinearLayout cal = pageBody(calibrationPage);
        TextView calTitle=text("Calibration",25,Color.WHITE); calTitle.setGravity(Gravity.START); calTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(cal,calTitle);
        calibrationInstructions=text("Empty the hook, keep it still for five seconds, then tap SET ZERO. The app checks empty and loaded readings twice.",14,muted); calibrationInstructions.setGravity(Gravity.START); add(cal,calibrationInstructions);
        TextView step1=text("1   EMPTY",13,orange); step1.setGravity(Gravity.START); step1.setTypeface(Typeface.DEFAULT,Typeface.BOLD); add(cal,step1);
        zeroButton = button("SET ZERO"); zeroButton.setOnClickListener(v -> setZero()); add(cal,zeroButton);
        TextView step2=text("2   KNOWN LOAD",13,orange); step2.setGravity(Gravity.START); step2.setTypeface(Typeface.DEFAULT,Typeface.BOLD); step2.setPadding(dp(8),dp(14),dp(8),dp(2)); add(cal,step2);
        knownWeight = new EditText(this); knownWeight.setHint("Weight (kg)"); knownWeight.setTextSize(17); knownWeight.setTextColor(Color.WHITE); knownWeight.setHintTextColor(Color.rgb(110,125,141)); knownWeight.setPadding(dp(16),dp(8),dp(16),dp(8)); knownWeight.setBackground(background(card,14));
        knownWeight.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL); LinearLayout.LayoutParams inputLp=new LinearLayout.LayoutParams(-1,dp(52)); inputLp.setMargins(0,dp(4),0,dp(8)); cal.addView(knownWeight,inputLp);
        calibrateButton=button("CAPTURE LOAD"); calibrateButton.setBackground(background(orange,16)); calibrateButton.setOnClickListener(v -> calibrateKnownLoad()); calibrateButton.setEnabled(false); add(cal,calibrateButton);
        calibration=text("",13,muted); calibration.setGravity(Gravity.START); calibration.setPadding(dp(8),dp(12),dp(8),dp(8)); add(cal,calibration);

        LinearLayout nav=new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL); nav.setPadding(dp(10),dp(7),dp(10),dp(10)); nav.setBackgroundColor(Color.rgb(15,22,30));
        scaleTab=tab("SCALE"); calibrationTab=tab("CALIBRATE"); scaleTab.setOnClickListener(v -> showPage(true)); calibrationTab.setOnClickListener(v -> showPage(false));
        nav.addView(scaleTab,new LinearLayout.LayoutParams(0,dp(52),1)); nav.addView(calibrationTab,new LinearLayout.LayoutParams(0,dp(52),1)); shell.addView(nav);
        shell.setOnApplyWindowInsetsListener((view,insets) -> {
            header.setPadding(dp(20),dp(18)+insets.getSystemWindowInsetTop(),dp(20),dp(8));
            nav.setPadding(dp(10),dp(7),dp(10),dp(10)+insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(shell); showPage(true); updateCalibrationText(); updateDisplay();
    }

    private ScrollView page(){ ScrollView s=new ScrollView(this); s.setFillViewport(true); s.setClipToPadding(false); return s; }
    private LinearLayout pageBody(ScrollView page){ LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16),dp(10),dp(16),dp(18)); page.addView(body); return body; }
    private TextView infoTile(String label,int color){ TextView t=text(label,15,color); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(82),1); lp.setMargins(dp(5),dp(5),dp(5),dp(5)); t.setLayoutParams(lp); t.setBackground(background(Color.rgb(22,30,40),18)); return t; }
    private TextView tab(String label){ TextView t=text(label,14,Color.rgb(151,163,178)); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setLetterSpacing(.08f); t.setGravity(Gravity.CENTER); return t; }
    private void showPage(boolean scale){
        scalePage.setVisibility(scale?View.VISIBLE:View.GONE); calibrationPage.setVisibility(scale?View.GONE:View.VISIBLE);
        scaleTab.setTextColor(scale?Color.rgb(255,145,40):Color.rgb(151,163,178)); calibrationTab.setTextColor(scale?Color.rgb(151,163,178):Color.rgb(255,145,40));
        scaleTab.setBackground(scale?background(Color.rgb(37,48,61),16):null); calibrationTab.setBackground(scale?null:background(Color.rgb(37,48,61),16));
    }

    private static class DotMatrixView extends View {
        private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG), on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String shown = "----";
        DotMatrixView(Context context) {
            super(context); setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            off.setColor(Color.rgb(48,42,29)); on.setColor(Color.rgb(255,178,46)); on.setShadowLayer(12,0,0,Color.rgb(255,130,0));
        }
        void setValue(String value){ shown=value==null?"":value; setContentDescription(shown); invalidate(); }
        private String[] glyph(char c){
            switch(c){
                case '0': return new String[]{"11111","10001","10011","10101","11001","10001","11111"};
                case '1': return new String[]{"00100","01100","00100","00100","00100","00100","01110"};
                case '2': return new String[]{"11110","00001","00001","11110","10000","10000","11111"};
                case '3': return new String[]{"11110","00001","00001","01110","00001","00001","11110"};
                case '4': return new String[]{"10010","10010","10010","11111","00010","00010","00010"};
                case '5': return new String[]{"11111","10000","10000","11110","00001","00001","11110"};
                case '6': return new String[]{"01111","10000","10000","11110","10001","10001","01110"};
                case '7': return new String[]{"11111","00001","00010","00100","01000","01000","01000"};
                case '8': return new String[]{"01110","10001","10001","01110","10001","10001","01110"};
                case '9': return new String[]{"01110","10001","10001","01111","00001","00001","11110"};
                case '-': return new String[]{"00000","00000","00000","11111","00000","00000","00000"};
                case '.': return new String[]{"0","0","0","0","0","1","1"};
                default: return new String[]{"00000","00000","00000","00000","00000","00000","00000"};
            }
        }
        @Override protected void onDraw(Canvas canvas){ super.onDraw(canvas); if(shown.isEmpty())return;
            int columns=0; for(char c:shown.toCharArray())columns+=(c=='.'?2:6); columns=Math.max(1,columns-1);
            float cell=Math.min(getHeight()/9f,getWidth()/(columns+2f)); float radius=cell*.25f;
            float startX=(getWidth()-columns*cell)/2f+cell*.5f, startY=(getHeight()-7*cell)/2f+cell*.5f, x=startX;
            for(char c:shown.toCharArray()){ String[] rows=glyph(c); int width=c=='.'?1:5;
                for(int row=0;row<7;row++)for(int col=0;col<width;col++)canvas.drawCircle(x+col*cell,startY+row*cell,radius,rows[row].charAt(col)=='1'?on:off);
                x+=(width+1)*cell;
            }
        }
    }

    private void requestPermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE); return;
        }
        if (Build.VERSION.SDK_INT < 31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLE); return;
        }
        startScan();
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) { super.onRequestPermissionsResult(r,p,g); if(r==REQUEST_BLE) startScan(); }

    @SuppressWarnings("MissingPermission") private void startScan() {
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager.getAdapter();
        if(adapter==null || !adapter.isEnabled()){ status.setText("Turn Bluetooth on"); return; }
        if(gatt!=null){ gatt.close(); gatt=null; }
        clearLiveReading();
        scanner=adapter.getBluetoothLeScanner(); scanning=true; status.setText("Searching for " + DEVICE_NAME + "…"); scanner.startScan(scanCallback);
        handler.postDelayed(() -> { if(scanning){ stopScan(); status.setText("Scale not found — tap Connect"); } }, 15000);
    }
    @SuppressWarnings("MissingPermission") private void stopScan(){ if(scanner!=null && scanning) scanner.stopScan(scanCallback); scanning=false; }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type, ScanResult result){
            String name=null; if(result.getScanRecord()!=null) name=result.getScanRecord().getDeviceName();
            if(name==null) try{name=result.getDevice().getName();}catch(SecurityException ignored){}
            if(DEVICE_NAME.equals(name)){ stopScan(); status.setText("Connecting…"); connect(result.getDevice()); }
        }
        @Override public void onScanFailed(int code){ status.setText("Bluetooth scan error " + code); scanning=false; }
    };
    @SuppressWarnings("MissingPermission") private void connect(BluetoothDevice device){ gatt=device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE); }

    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int statusCode,int newState){ runOnUiThread(() -> {
            if(newState==BluetoothProfile.STATE_CONNECTED){ status.setText("Connected — waiting for scale"); try{g.discoverServices();}catch(SecurityException ignored){} }
            else { status.setText("Disconnected — tap Connect"); clearLiveReading(); }
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g,int statusCode){
            BluetoothGattService s=g.getService(SERVICE_UUID); BluetoothGattCharacteristic c=s==null?null:s.getCharacteristic(TX_UUID);
            if(c==null){ runOnUiThread(() -> status.setText("Weight service unavailable")); return; }
            try { g.setCharacteristicNotification(c,true); BluetoothGattDescriptor d=c.getDescriptor(CCCD_UUID); if(d!=null){
                if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
            }} catch(SecurityException ignored){}
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){ handleBytes(c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] bytes){ handleBytes(bytes); }
    };

    private void handleBytes(byte[] bytes){
        if(bytes==null)return; String line=new String(bytes,StandardCharsets.UTF_8).trim(); int at=line.indexOf("RAW="); if(at<0)return;
        int end=line.indexOf(',',at); String number=end<0?line.substring(at+4):line.substring(at+4,end);
        try { long next=Long.parseLong(number.trim()); runOnUiThread(() -> {
            long now=SystemClock.elapsedRealtime(); if(lastPacketMs==0||now-lastPacketMs>1500)rawFilter.clear();
            raw=next; rawFilter.add(next);
            lastPacketMs=now; status.setText("Connected"); updateDisplay();
            Log.d(TAG,String.format(Locale.US,"bridge=%s sample=%d filtered=%.2f spread=%.2f n=%d stable=%s",line,next,rawFilter.value(),rawFilter.centralSpread(),rawFilter.size(),rawIsStable()));
        }); } catch(NumberFormatException ignored){}
    }

    private boolean rawIsStable(){ return rawFilter.isStable(countsPerKg); }
    private double grossKg(){ return countsPerKg==0 ? 0 : (rawFilter.value()-zeroRaw)/countsPerKg; }
    private void updateDisplay(){
        if(raw==0){ value.setValue("----"); unitLabel.setText(countsPerKg==0?"SETUP NEEDED":(tonnes?"TONNES":"KILOGRAMS")); stability.setText("WAITING"); updateButtons(); return; }
        if(countsPerKg==0){ value.setValue("----"); unitLabel.setText("SETUP NEEDED"); mode.setText("WEIGHT"); stability.setText("OPEN CALIBRATE"); updateButtons(); return; }
        double gross=grossKg(), shown=showNet?gross-tareKg:gross;
        recentKg.addLast(shown); while(recentKg.size()>10)recentKg.removeFirst(); double min=Double.MAX_VALUE,max=-Double.MAX_VALUE; for(double x:recentKg){min=Math.min(min,x);max=Math.max(max,x);}
        boolean stable=rawIsStable()&&recentKg.size()>=6&&max-min<=5.0; boolean overload=Math.abs(gross)>CAPACITY_KG;
        double display=tonnes?shown/1000.0:shown; String unit=tonnes?"TONNES":"KILOGRAMS"; value.setValue(String.format(Locale.US,tonnes?"%.3f":"%.1f",display)); unitLabel.setText(unit);
        mode.setText(showNet?String.format(Locale.US,"NET   •   TARE %.1f kg",tareKg):"GROSS");
        stability.setText(overload?"OVERLOAD — ABOVE 15,000 kg":(stable?"● STABLE":"○ MOVING")); stability.setTextColor(overload?Color.RED:(stable?Color.rgb(76,217,100):Color.rgb(255,183,77)));
        updateButtons();
    }
    private void updateButtons(){ boolean data=raw!=0, ready=data&&countsPerKg!=0; tareButton.setEnabled(ready); displayZeroButton.setEnabled(ready); zeroButton.setEnabled(data); grossNetButton.setEnabled(ready); grossNetButton.setText(showNet?"GROSS":"NET"); unitButton.setText(tonnes?"KG":"TONNES"); }
    private void setZero(){
        if(raw==0)return;
        if(!rawFilter.isReady()){Toast.makeText(this,"Keep the empty hook still — collecting " + rawFilter.size() + "/" + WeightFilter.WINDOW_SIZE,Toast.LENGTH_LONG).show();return;}
        if(!rawFilter.isStable(0)){Toast.makeText(this,"The hook is still moving. Wait until the reading settles.",Toast.LENGTH_LONG).show();return;}
        firstZeroRaw=rawFilter.value(); firstZeroSpread=rawFilter.centralSpread(); calibrationStage=1;
        Log.i(TAG,String.format(Locale.US,"calibration zero1=%.2f spread=%.2f",firstZeroRaw,firstZeroSpread));
        rawFilter.clear(); recentKg.clear(); calibrateButton.setEnabled(true); calibrateButton.setText("CAPTURE LOAD");
        calibrationInstructions.setText("Apply the known load, enter its weight, keep still for five seconds, then tap CAPTURE LOAD.");
        Toast.makeText(this,"First empty reading captured. Apply the known load.",Toast.LENGTH_LONG).show();
    }
    private void setTare(){ if(countsPerKg==0)return; if(!rawIsStable()){Toast.makeText(this,"Wait for a stable reading",Toast.LENGTH_SHORT).show();return;} tareKg=grossKg(); recentKg.clear(); showNet=true; Toast.makeText(this,"Tare set",Toast.LENGTH_SHORT).show(); updateDisplay(); }
    private void calibrateKnownLoad(){
        if(raw==0 || calibrationStage==0){ Toast.makeText(this,"Capture empty zero first",Toast.LENGTH_SHORT).show(); return; }
        if(!rawFilter.isReady()){Toast.makeText(this,"Keep still — collecting " + rawFilter.size() + "/" + WeightFilter.WINDOW_SIZE,Toast.LENGTH_LONG).show();return;}
        if(!rawFilter.isStable(0)){Toast.makeText(this,"The scale is still moving. Wait until it settles.",Toast.LENGTH_LONG).show();return;}
        double point=rawFilter.value(), spread=rawFilter.centralSpread();
        if(calibrationStage==1){
            try { calibrationKg=parseKnownKg(knownWeight.getText().toString()); }
            catch(NumberFormatException e){ Toast.makeText(this,"Enter the load in kg, for example 500 or 1.5 t",Toast.LENGTH_LONG).show(); return; }
            if(calibrationKg<=0||calibrationKg>CAPACITY_KG){ Toast.makeText(this,"Weight must be between 0 and 15,000 kg",Toast.LENGTH_LONG).show(); return; }
            double span=point-firstZeroRaw;
            if(Math.abs(span)<WeightFilter.minimumCalibrationSpan(firstZeroSpread,spread)){ Toast.makeText(this,"The measured change is too small compared with scale movement. Use a heavier known load.",Toast.LENGTH_LONG).show(); return; }
            firstLoadRaw=point; firstLoadSpread=spread; calibrationStage=2; rawFilter.clear();
            Log.i(TAG,String.format(Locale.US,"calibration load1=%.2f kg=%.2f spread=%.2f",firstLoadRaw,calibrationKg,firstLoadSpread));
            calibrateButton.setText("VERIFY EMPTY"); calibrationInstructions.setText("Remove the load, keep still for five seconds, then tap VERIFY EMPTY.");
            Toast.makeText(this,"Load captured. Remove it and verify empty return.",Toast.LENGTH_LONG).show(); return;
        }
        double preliminaryFactor=(firstLoadRaw-firstZeroRaw)/calibrationKg;
        double toleranceKg=WeightFilter.repeatabilityToleranceKg(calibrationKg);
        if(calibrationStage==2){
            double zeroErrorKg=Math.abs(point-firstZeroRaw)/Math.abs(preliminaryFactor);
            Log.i(TAG,String.format(Locale.US,"calibration zero2=%.2f errorKg=%.3f spread=%.2f",point,zeroErrorKg,spread));
            if(zeroErrorKg>toleranceKg){ failCalibration(String.format(Locale.US,"The empty reading did not return: %.1f kg error. Check the hook and repeat calibration.",zeroErrorKg)); return; }
            secondZeroRaw=point; secondZeroSpread=spread; calibrationStage=3; rawFilter.clear();
            calibrateButton.setText("VERIFY LOAD"); calibrationInstructions.setText("Apply the same known load again, keep still for five seconds, then tap VERIFY LOAD.");
            Toast.makeText(this,"Empty return passed. Apply the same load again.",Toast.LENGTH_LONG).show(); return;
        }
        double loadErrorKg=Math.abs(point-firstLoadRaw)/Math.abs(preliminaryFactor);
        Log.i(TAG,String.format(Locale.US,"calibration load2=%.2f errorKg=%.3f spread=%.2f",point,loadErrorKg,spread));
        if(loadErrorKg>toleranceKg){ failCalibration(String.format(Locale.US,"The repeated load differs by %.1f kg. Check mounting and repeat calibration.",loadErrorKg)); return; }
        zeroRaw=(firstZeroRaw+secondZeroRaw)/2.0; zeroSpread=Math.max(firstZeroSpread,secondZeroSpread);
        double loadedRaw=(firstLoadRaw+point)/2.0;
        countsPerKg=(loadedRaw-zeroRaw)/calibrationKg; hasZero=true; tareKg=0; recentKg.clear(); saveCalibration();
        calibrationStage=0; calibrateButton.setEnabled(false); calibrateButton.setText("CAPTURE LOAD");
        calibrationInstructions.setText("Calibration verified. Tap SET ZERO to start a new calibration.");
        updateCalibrationText(); updateDisplay(); Toast.makeText(this,"Repeatable calibration saved",Toast.LENGTH_LONG).show();
        if(calibrationKg<CAPACITY_KG*.02) Toast.makeText(this,"Light-load calibration saved. Recalibrate with a heavier certified load before weighing heavy loads.",Toast.LENGTH_LONG).show();
    }

    private void failCalibration(String message){
        Log.w(TAG,"calibration rejected: "+message); calibrationStage=0; rawFilter.clear(); recentKg.clear();
        calibrateButton.setEnabled(false); calibrateButton.setText("CAPTURE LOAD");
        calibrationInstructions.setText("Calibration rejected. Empty the hook, keep still for five seconds, and tap SET ZERO to restart.");
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private double parseKnownKg(String entered){
        String s=entered==null?"":entered.trim().toLowerCase(Locale.ROOT);
        boolean tonnes=s.endsWith("t")||s.contains("ton")||s.contains("tonne");
        StringBuilder normalized=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i); int digit=Character.digit(c,10);
            if(digit>=0)normalized.append((char)('0'+digit));
            else if(c=='.'||c==','||c=='\u066B'||c=='\u066C')normalized.append(c=='\u066B'?'.':c=='\u066C'?',':c);
        }
        String n=normalized.toString();
        if(n.matches("\\d{1,3}(,\\d{3})+")) n=n.replace(",","");
        else if(n.indexOf(',')>=0&&n.indexOf('.')>=0){
            if(n.lastIndexOf(',')>n.lastIndexOf('.'))n=n.replace(".","").replace(',','.'); else n=n.replace(",","");
        } else n=n.replace(',','.');
        if(n.isEmpty())throw new NumberFormatException();
        double value=Double.parseDouble(n); return tonnes?value*1000.0:value;
    }
    private void saveCalibration(){ prefs.edit().putInt("calibrationVersion",CALIBRATION_VERSION).putBoolean("hasZero",hasZero).putLong("zeroRawV2",Double.doubleToRawLongBits(zeroRaw)).putLong("zeroSpread",Double.doubleToRawLongBits(zeroSpread)).putLong("countsPerKg",Double.doubleToRawLongBits(countsPerKg)).apply(); }
    private void updateCalibrationText(){ calibration.setText(countsPerKg==0?(hasZero?"Empty reading saved — apply a known load":"Not calibrated"):"Calibration saved"); }
    private void clearLiveReading(){ raw=0; lastPacketMs=0; rawFilter.clear(); recentKg.clear(); value.setValue("----"); stability.setText("WAITING"); updateButtons(); }
    private final Runnable connectionWatchdog=new Runnable(){ public void run(){ if(lastPacketMs>0 && SystemClock.elapsedRealtime()-lastPacketMs>2500){ status.setText("Connected — no data"); stability.setText("WAITING"); } handler.postDelayed(this,1000); }};
    @Override protected void onDestroy(){ super.onDestroy(); handler.removeCallbacksAndMessages(null); stopScan(); try{if(gatt!=null)gatt.close();}catch(SecurityException ignored){} }
}
