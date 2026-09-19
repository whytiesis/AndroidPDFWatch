package cn.paperpdf.reader;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import android.util.AtomicFile;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int PURPLE=0xff6154C7, INK=0xff272535, MUTED=0xff85828E, BG=0xffF7F6F2;
    private static final int OPEN=10,SAVE=11,FOLDER=12,WRITE_ORIGINAL=13;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root,bar,readerNav;
    private HorizontalScrollView readerTools;
    private FrameLayout content;
    private TextView title,subtitle,pager,hint,saveButton;
    private PageCanvas pageView;
    private ContinuousView continuousView;
    private PdfRenderer renderer;
    private File source,pendingExport;
    private DraftStore drafts;
    private String draftId="",name="",sourceUri="",savedModel="",saveStatus="已打开";
    private ArrayList<PdfEngine.Sheet> sheets=new ArrayList<>();
    private final ArrayDeque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private int current,mode,renderGeneration;
    private boolean busy,night,continuous,canModify=true,destroyed,viewing,settingsPage,settingsFromViewer;
    private Bitmap displayed;
    private float readingFraction;
    private boolean fitContent=true;
    private boolean readerChromeVisible=true;
    private boolean returnToContinuous;
    private float returnContinuousFraction;
    private File pendingOverwrite;
    private final ArrayList<TextView> modes=new ArrayList<>();
    private Dialog progress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); PDFBoxResourceLoader.init(getApplicationContext());
        drafts=new DraftStore(this);
        draftId=getPreferences(0).getString("active_draft","");
        source=drafts.exists(draftId)?drafts.pdf(draftId):new File(getFilesDir(),"current.pdf");
        night=getPreferences(0).getBoolean("night",false);
        continuous=getPreferences(0).getBoolean("continuous",false);
        fitContent=getPreferences(0).getBoolean("fit_content",true);
        if(state!=null) readerChromeVisible=state.getBoolean("readerChromeVisible",true);
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        if(Build.VERSION.SDK_INT>=28) {
            WindowManager.LayoutParams attributes=getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=27) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else getWindow().setNavigationBarColor(0xff272535);
        home();
        Uri incoming=incoming(getIntent());
        if(state!=null&&state.getBoolean("viewing")&&source.isFile()) {
            String path=state.getString("pending"); if(path!=null) pendingExport=new File(path);
            restore();
        } else if(incoming!=null) { if("file".equals(incoming.getScheme())) openUri(incoming,null); else offerImport(incoming); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); Uri uri=incoming(intent);
        if(uri!=null) confirmLeave(()->{ if("file".equals(uri.getScheme())) openUri(uri,null); else offerImport(uri); });
    }
    private Uri incoming(Intent intent) {
        if(Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
        if(Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(stream!=null) return stream;
            ClipData clips=intent.getClipData();
            if(clips!=null&&clips.getItemCount()>0) return clips.getItemAt(0).getUri();
        }
        return null;
    }
    private int themeColor(int color) {
        if(!night) return color;
        if(color==BG) return 0xff202127;
        if(color==INK) return 0xffEAE8F0;
        if(color==MUTED||color==0xff706A89) return 0xffB8B4C4;
        if(color==0xffEDEAF9) return 0xff353044;
        return color;
    }
    private Context dialogContext() {
        return new ContextThemeWrapper(this,night?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(dialogContext()); }
    private TextView iconButton(String symbol,String description,Runnable action) {
        TextView v=button(symbol,action,false); v.setTextSize(22); v.setPadding(0,0,0,0);
        v.setMinWidth(0); v.setMinHeight(0); v.setSingleLine(true); v.setIncludeFontPadding(false); v.setContentDescription(description); return v;
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(night&&color==Color.WHITE?0xff292A33:themeColor(color)); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String text,int size,int color,boolean bold) {
        TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(night&&color==PURPLE?0xffCEC2FF:themeColor(color));
        if(bold) v.setTypeface(null,Typeface.BOLD); return v;
    }
    private TextView button(String text,Runnable action,boolean primary) {
        TextView v=label(text,14,primary?Color.WHITE:PURPLE,true); v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(48)); v.setPadding(dp(16),dp(10),dp(16),dp(10));
        v.setBackground(shape(primary?PURPLE:0xffEDEAF9,14)); v.setOnClickListener(w->{ if(!busy) action.run(); });
        v.setFocusable(true); return v;
    }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void base() {
        renderGeneration++;
        if(continuousView!=null) { continuousView.dispose(); continuousView=null; }
        root=vertical(); root.setBackgroundColor(themeColor(BG));
        getWindow().setStatusBarColor(themeColor(BG)); getWindow().setNavigationBarColor(themeColor(BG));
        applySystemBars();
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,i.bottom);
            } else {
                int left=insets.getSystemWindowInsetLeft(),top=insets.getSystemWindowInsetTop(),right=insets.getSystemWindowInsetRight(),bottom=insets.getSystemWindowInsetBottom();
                if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null) {
                    DisplayCutout cutout=insets.getDisplayCutout();
                    left=Math.max(left,cutout.getSafeInsetLeft()); top=Math.max(top,cutout.getSafeInsetTop());
                    right=Math.max(right,cutout.getSafeInsetRight()); bottom=Math.max(bottom,cutout.getSafeInsetBottom());
                }
                v.setPadding(left,top,right,bottom);
            }
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }
    private void applySystemBars() {
        boolean hidden=viewing&&!settingsPage&&!readerChromeVisible;
        View decor=getWindow().getDecorView();
        int flags=night?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=26&&!night) flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if(Build.VERSION.SDK_INT>=30) {
            WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null) {
                int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(night?0:appearance,appearance);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if(hidden) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            flags|=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if(hidden) flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(flags);
        }
    }
    private void toggleReaderChrome() {
        if(!viewing||settingsPage||busy||mode!=0) return;
        readerChromeVisible=!readerChromeVisible; applyReaderChrome();
    }
    private void applyReaderChrome() {
        if(!viewing||settingsPage||bar==null||readerNav==null||readerTools==null) return;
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        int visibility=readerChromeVisible?View.VISIBLE:View.GONE;
        bar.setVisibility(visibility); readerNav.setVisibility(visibility); readerTools.setVisibility(visibility);
        hint.setVisibility(readerChromeVisible&&!landscape?View.VISIBLE:View.GONE);
        subtitle.setVisibility(landscape?View.GONE:View.VISIBLE);
        ViewGroup.LayoutParams params=bar.getLayoutParams(); params.height=dp(landscape?52:64); bar.setLayoutParams(params);
        applySystemBars(); root.requestApplyInsets();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Keep the live reader and annotation model; its resize callbacks preserve the reading anchor.
        applyReaderChrome(); if(root!=null) root.requestApplyInsets();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus); if(hasFocus) applySystemBars();
    }
    private void space(LinearLayout l,int h) { l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h))); }
    private void home() {
        capturePosition();
        if(returnToContinuous) { continuous=true; returnToContinuous=false; }
        viewing=false; settingsPage=false; settingsFromViewer=false; base();
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=vertical(); body.setPadding(dp(24),dp(26),dp(24),dp(24)); scroll.addView(body);
        LinearLayout brand=new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo=label("▤",29,Color.WHITE,true); logo.setGravity(Gravity.CENTER); logo.setBackground(shape(PURPLE,16));
        brand.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout names=vertical(); names.setPadding(dp(13),0,0,0); names.addView(label("纸阅 PDF",22,INK,true)); names.addView(label("PAPER · READ & ANNOTATE",10,MUTED,false)); brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView settingsButton=iconButton("⚙","设置",this::openSettings); brand.addView(settingsButton,new LinearLayout.LayoutParams(dp(52),dp(48)));
        body.addView(brand); space(body,34);
        body.addView(label("每一页，都有新想法。",27,INK,true)); space(body,9);
        body.addView(label("打开你的文档，让阅读与记录在一起。",14,MUTED,false)); space(body,24);
        LinearLayout hero=vertical(); hero.setPadding(dp(24),dp(26),dp(24),dp(24)); hero.setBackground(shape(0xffEDEAF9,24));
        hero.addView(label("PDF",42,PURPLE,true)); space(hero,12); hero.addView(label("你的随身文档空间",20,INK,true)); space(hero,8);
        hero.addView(label("本地阅读 · 自由批注 · 随时保存\n双指缩放，清晰查看每一处细节",14,0xff706A89,false)); space(hero,22);
        hero.addView(button("＋  打开手机中的 PDF",()->confirmLeave(this::openLibrary),true),new LinearLayout.LayoutParams(-1,dp(52)));
        body.addView(hero); space(body,22);
        String last=getPreferences(0).getString("last_draft","");
        boolean hasDraft=drafts.exists(last),hasLegacy=new File(getFilesDir(),"session.json").isFile()&&new File(getFilesDir(),"current.pdf").isFile();
        if(hasDraft||hasLegacy) {
            String resume=(hasDraft&&drafts.dirty(last))?"继续上次阅读 · 有编辑草稿":"继续上次阅读";
            body.addView(button(resume,this::restore,false)); space(body,18);
        }
        LinearLayout recentTitle=new LinearLayout(this); recentTitle.addView(label("最近打开",17,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView clear=label("清空",13,MUTED,false); clear.setPadding(dp(12),dp(8),0,dp(8)); clear.setOnClickListener(v->{getPreferences(0).edit().remove("recent").apply(); home();}); recentTitle.addView(clear); body.addView(recentTitle);
        JSONArray recent=recent();
        if(recent.length()==0) { space(body,10); body.addView(label("打开的文档会出现在这里",14,MUTED,false)); }
        for(int i=0;i<recent.length();i++) {
            JSONObject item=recent.optJSONObject(i); if(item==null) continue;
            String itemUri=item.optString("uri"),itemName=item.optString("name");
            boolean hasChanges=drafts.dirty(drafts.id(positionKey(itemUri)));
            LinearLayout row=vertical(); row.setPadding(dp(14),dp(12),dp(14),dp(12)); row.setBackground(shape(Color.WHITE,12));
            TextView rowTitle=label("▤   "+itemName,15,INK,false); rowTitle.setSingleLine(true); rowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(rowTitle);
            if(hasChanges) { TextView badge=label("编辑草稿已自动保留",11,PURPLE,true); badge.setPadding(dp(28),dp(4),0,0); row.addView(badge); }
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(8); body.addView(row,p);
            row.setOnClickListener(v->confirmLeave(()->{
                String id=drafts.id(positionKey(itemUri)); if(drafts.exists(id)) restoreDraft(id); else openUri(Uri.parse(itemUri),null);
            }));
        }
        space(body,24); TextView foot=label("文件留在设备上",12,MUTED,false); foot.setGravity(Gravity.CENTER); body.addView(foot);
    }

    private void openSettings() {
        capturePosition(); settingsFromViewer=viewing;
        settings();
    }

    private void settings() {
        settingsPage=true; base();
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(8),dp(4),dp(12),dp(4));
        TextView back=iconButton("‹","返回",()->{ if(settingsFromViewer&&renderer!=null) viewer(); else home(); });
        top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        top.addView(label("设置",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top,new LinearLayout.LayoutParams(-1,dp(64)));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=vertical(); body.setPadding(dp(20),dp(10),dp(20),dp(26)); scroll.addView(body);
        body.addView(label("PDF 文件目录",18,INK,true)); space(body,7);
        body.addView(label("“打开手机中的 PDF”会列出下面目录里的全部 PDF。目录只在你授权后读取。",13,MUTED,false)); space(body,14);
        JSONArray saved=folders();
        if(saved.length()==0) { TextView empty=label("还没有添加目录",14,MUTED,false); empty.setPadding(dp(12),dp(14),dp(12),dp(14)); body.addView(empty); }
        for(int i=0;i<saved.length();i++) {
            JSONObject item=saved.optJSONObject(i); if(item==null) continue;
            final String uri=item.optString("uri"), display=item.optString("name","PDF 文件夹"), kind=item.optString("kind","normal");
            LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(11),dp(7),dp(11)); row.setBackground(shape(Color.WHITE,14));
            LinearLayout text=vertical(); text.addView(label(display,14,INK,true)); text.addView(label("微信 / QQ 文件夹".equals(kind)?"微信 / QQ":"普通目录",11,MUTED,false));
            row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            TextView remove=label("移除",12,0xffB14C59,true); remove.setGravity(Gravity.CENTER); remove.setPadding(dp(10),dp(8),dp(8),dp(8));
            row.addView(remove,new LinearLayout.LayoutParams(dp(54),dp(42))); body.addView(row,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams rp=(LinearLayout.LayoutParams)row.getLayoutParams(); rp.topMargin=dp(7);
            row.setOnClickListener(v->browseFolder(Uri.parse(uri)));
            remove.setOnClickListener(v->removeFolder(Uri.parse(uri)));
        }
        space(body,12); body.addView(button("＋  添加普通目录",()->chooseFolder("普通目录"),false),new LinearLayout.LayoutParams(-1,dp(44)));
        space(body,8); body.addView(button("＋  添加微信 / QQ 文件夹",()->chooseFolder("微信 / QQ 文件夹"),false),new LinearLayout.LayoutParams(-1,dp(44)));
        space(body,28); body.addView(label("阅读方式",18,INK,true)); space(body,7);
        LinearLayout reading=vertical(); reading.setPadding(dp(12),dp(4),dp(12),dp(4)); reading.setBackground(shape(night?0xff292A33:Color.WHITE,14));
        Switch continuousSwitch=new Switch(this); continuousSwitch.setText("连续滚动阅读"); continuousSwitch.setTextSize(15); continuousSwitch.setTextColor(themeColor(INK)); continuousSwitch.setChecked(continuous||returnToContinuous); continuousSwitch.setPadding(0,dp(7),0,dp(7));
        continuousSwitch.setOnCheckedChangeListener((view,checked)->{ continuous=checked; returnToContinuous=false; getPreferences(0).edit().putBoolean("continuous",checked).apply(); }); reading.addView(continuousSwitch);
        reading.addView(label("关闭时保留单页左右翻页；阅读页右上角也可快速切换。",12,MUTED,false)); body.addView(reading);
        space(body,12);
        Switch fitSwitch=new Switch(this); fitSwitch.setText("滚动阅读适合正文宽度"); fitSwitch.setTextSize(15); fitSwitch.setTextColor(themeColor(INK)); fitSwitch.setChecked(fitContent);
        fitSwitch.setPadding(dp(12),dp(10),dp(12),dp(10));
        fitSwitch.setOnCheckedChangeListener((v,checked)->{fitContent=checked;getPreferences(0).edit().putBoolean("fit_content",checked).apply();});
        body.addView(fitSwitch); body.addView(label("自动收起左右白边；双指或双击还可放大。关闭可显示完整页宽。",12,MUTED,false));
        space(body,12); LinearLayout appearance=vertical(); appearance.setPadding(dp(12),dp(4),dp(12),dp(4)); appearance.setBackground(shape(night?0xff292A33:Color.WHITE,14));
        Switch nightSwitch=new Switch(this); nightSwitch.setText("夜间阅读"); nightSwitch.setTextSize(15); nightSwitch.setTextColor(themeColor(INK)); nightSwitch.setChecked(night); nightSwitch.setPadding(0,dp(7),0,dp(7));
        nightSwitch.setOnCheckedChangeListener((view,checked)->{ night=checked; getPreferences(0).edit().putBoolean("night",checked).apply(); settings(); }); appearance.addView(nightSwitch);
        appearance.addView(label("文件只保存在设备本地，不上传。",12,MUTED,false)); body.addView(appearance);
        space(body,28); body.addView(label("纸阅 PDF v"+BuildConfig.VERSION_NAME,13,MUTED,false));
        space(body,8); TextView help=label("使用说明与兼容范围",14,PURPLE,true); help.setPadding(0,dp(10),0,dp(10)); help.setOnClickListener(v->help()); body.addView(help);
    }
    private JSONArray recent() {
        try { return new JSONArray(getPreferences(0).getString("recent","[]")); } catch(JSONException e) { return new JSONArray(); }
    }
    private void remember() {
        if(sourceUri.isEmpty()) return;
        try {
            JSONArray old=recent(),next=new JSONArray(); next.put(new JSONObject().put("name",name).put("uri",sourceUri));
            for(int i=0;i<old.length()&&next.length()<12;i++) if(!sourceUri.equals(old.getJSONObject(i).optString("uri"))) next.put(old.get(i));
            getPreferences(0).edit().putString("recent",next.toString()).apply();
        } catch(JSONException ignored) { }
    }
    private void pick() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent,OPEN); } catch(ActivityNotFoundException e) { error("未找到系统文件选择器。请从文件管理器选择 PDF，使用纸阅打开。"); }
    }
    private void openLibrary() {
        task("正在扫描已添加目录…",()->{
            ArrayList<PdfFolderIndex.Entry> files=new ArrayList<>(); JSONArray saved=folders();
            for(int i=0;i<saved.length();i++) {
                JSONObject item=saved.optJSONObject(i); if(item==null) continue;
                String value=item.optString("uri"); if(value.isEmpty()) continue;
                try { files.addAll(PdfFolderIndex.scan(getContentResolver(),Uri.parse(value))); }
                catch(SecurityException ignored) { }
            }
            Collections.sort(files,new Comparator<PdfFolderIndex.Entry>() {
                @Override public int compare(PdfFolderIndex.Entry first,PdfFolderIndex.Entry second) {
                    int newest=Long.compare(second.modified,first.modified); return newest!=0?newest:first.name.compareTo(second.name);
                }
            });
            return files;
        },this::showLibrary);
    }
    private void showLibrary(ArrayList<PdfFolderIndex.Entry> files) {
        LinearLayout panel=vertical(); panel.setPadding(dp(16),0,dp(16),dp(8));
        EditText search=new EditText(dialogContext()); search.setSingleLine(true); search.setHint(files.isEmpty()?"还没有可浏览的目录":"搜索文件名（共 "+files.size()+" 个）"); panel.addView(search);
        ListView list=new ListView(this); ArrayList<PdfFolderIndex.Entry> shown=new ArrayList<>(files);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(dialogContext(),android.R.layout.simple_list_item_1);
        for(PdfFolderIndex.Entry file:shown) adapter.add(file.name);
        list.setAdapter(adapter); panel.addView(list,new LinearLayout.LayoutParams(-1,dp(390)));
        AlertDialog dialog=dialog().setTitle("手机中的 PDF").setView(panel)
            .setPositiveButton("从文件中选择",(d,w)->pickChat())
            .setNeutralButton("设置目录",(d,w)->settings()).setNegativeButton("关闭",null).create();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                shown.clear(); adapter.clear(); String query=s.toString().toLowerCase(Locale.ROOT);
                for(PdfFolderIndex.Entry file:files) if(file.name.toLowerCase(Locale.ROOT).contains(query)) { shown.add(file); adapter.add(file.name); }
                adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(android.text.Editable editable) { }
        });
        list.setOnItemClickListener((parent,view,position,id)->{ PdfFolderIndex.Entry file=shown.get(position); dialog.dismiss(); confirmLeave(()->openUri(file.uri,null)); });
        dialog.show();
        if(files.isEmpty()) {
            search.setVisibility(View.GONE); list.setVisibility(View.GONE);
            TextView empty=label("还没有添加可浏览的目录。\n请先到设置添加普通目录或微信 / QQ 文件夹。",14,MUTED,false); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(16),dp(32),dp(16),dp(32));
            panel.addView(empty,1);
        }
    }
    private void pickChat() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(Build.VERSION.SDK_INT>=26) {
            Uri downloads=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,downloads);
        }
        try { startActivityForResult(intent,OPEN); }
        catch(ActivityNotFoundException e) { error("系统文件选择器不可用。请在微信或 QQ 中找到文件，点击“用其他应用打开”并选择纸阅 PDF。"); }
    }
    private JSONArray folders() {
        try {
            String stored=getPreferences(0).getString("pdf_folders",null);
            if(stored==null) stored=getPreferences(0).getString("chat_folders","[]");
            return new JSONArray(stored);
        }
        catch(JSONException e) { return new JSONArray(); }
    }
    private void saveFolders(JSONArray folders) {
        getPreferences(0).edit().putString("pdf_folders",folders.toString()).putString("chat_folders",folders.toString()).apply();
    }
    private String pendingFolderKind="普通目录";
    private void chooseFolder(String kind) {
        pendingFolderKind=kind;
        dialog().setTitle("添加"+kind)
            .setMessage("选择一个包含 PDF 的可访问文件夹，授权一次后即可在文件库浏览。Android 11 及以上无法授予其他应用的 Android/data 私有目录；遇到微信或 QQ 私有文件，请在聊天中点“用其他应用打开”或“分享”。")
            .setPositiveButton("选择文件夹",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(intent,FOLDER); }
                catch(ActivityNotFoundException e) { error("未找到系统文件夹选择器。"); }
            }).setNegativeButton("取消",null).show();
    }
    private String folderName(Uri uri) {
        try(Cursor c=getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
            if(c!=null&&c.moveToFirst()&&!c.isNull(0)) return c.getString(0);
        } catch(Exception ignored) { }
        return "聊天文件夹";
    }
    private void saveFolder(Uri uri) {
        try {
            JSONArray old=folders(),updated=new JSONArray();
            updated.put(new JSONObject().put("uri",uri.toString()).put("name",folderName(uri)).put("kind",pendingFolderKind));
            for(int i=0;i<old.length();i++) if(!uri.toString().equals(old.getJSONObject(i).optString("uri"))) updated.put(old.get(i));
            saveFolders(updated); settings(); toast("目录已添加");
        } catch(JSONException e) { error("保存文件夹失败，请重新选择。"); }
    }
    private void browseFolder(Uri uri) {
        task("正在查找 PDF…",()->PdfFolderIndex.scan(getContentResolver(),uri),files->{
            if(files.isEmpty()) { error("这个文件夹中没有可访问的 PDF。可试试“从文件中选择”（会优先打开 Download），或在微信 / QQ 中用“打开方式”发送给纸阅。"); return; }
            LinearLayout panel=vertical(); panel.setPadding(dp(16),0,dp(16),dp(8));
            EditText search=new EditText(dialogContext()); search.setSingleLine(true); search.setHint("搜索文件名（共 "+files.size()+" 个）"); panel.addView(search);
            ListView list=new ListView(this); ArrayList<PdfFolderIndex.Entry> shown=new ArrayList<>(files);
            ArrayAdapter<String> adapter=new ArrayAdapter<>(dialogContext(),android.R.layout.simple_list_item_1);
            for(PdfFolderIndex.Entry file:shown) adapter.add(file.name);
            list.setAdapter(adapter); panel.addView(list,new LinearLayout.LayoutParams(-1,dp(390)));
            AlertDialog dialog=dialog().setTitle("聊天文件夹中的 PDF").setView(panel)
                .setNegativeButton("关闭",null).setNeutralButton("移除此文件夹",(d,w)->removeFolder(uri)).create();
            search.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
                public void onTextChanged(CharSequence s,int start,int before,int count) {
                    shown.clear(); adapter.clear();
                    for(PdfFolderIndex.Entry file:files) if(file.name.toLowerCase(Locale.ROOT).contains(s.toString().toLowerCase(Locale.ROOT))) {
                        shown.add(file); adapter.add(file.name);
                    }
                    adapter.notifyDataSetChanged();
                }
                public void afterTextChanged(android.text.Editable e) { }
            });
            list.setOnItemClickListener((parent,view,position,id)->{
                Uri file=shown.get(position).uri; dialog.dismiss(); confirmLeave(()->openUri(file,null));
            });
            dialog.show();
        });
    }
    private void removeFolder(Uri uri) {
        try {
            JSONArray old=folders(),updated=new JSONArray();
            for(int i=0;i<old.length();i++) if(!uri.toString().equals(old.getJSONObject(i).optString("uri"))) updated.put(old.get(i));
            saveFolders(updated);
            try { getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException ignored) { }
            if(settingsPage) settings(); else home();
        } catch(JSONException e) { error("移除文件夹失败。"); }
    }
    private String fileName(Uri uri) {
        if("file".equals(uri.getScheme())&&uri.getPath()!=null) return new File(uri.getPath()).getName();
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null&&c.moveToFirst()&&!c.isNull(0)) return c.getString(0);
        } catch(Exception ignored) { }
        return "文档.pdf";
    }
    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void run(T value); }
    private <T> void task(String message,Job<T> job,Done<T> done) {
        task(message,job,done,null);
    }
    private <T> void task(String message,Job<T> job,Done<T> done,Runnable failed) {
        if(busy) return; busy=true;
        LinearLayout box=vertical(); box.setGravity(Gravity.CENTER); box.setPadding(dp(32),dp(24),dp(32),dp(24));
        ProgressBar spinner=new ProgressBar(this); box.addView(spinner,new LinearLayout.LayoutParams(dp(40),dp(40))); space(box,16); box.addView(label(message,14,INK,false));
        progress=dialog().setView(box).setCancelable(false).create(); progress.show();
        worker.execute(()->{
            try { T result=job.run(); runOnUiThread(()->{ finishBusy(); if(!destroyed) done.run(result); }); }
            catch(Exception | OutOfMemoryError e) { runOnUiThread(()->{finishBusy(); if(!destroyed) { if(failed!=null) failed.run(); error(e instanceof OutOfMemoryError?"文档内容过大，内存不足。请尝试较小的 PDF。":friendly(e)); }}); }
        });
    }
    private void finishBusy() { busy=false; if(progress!=null) { progress.dismiss(); progress=null; } }
    private String friendly(Throwable e) {
        if(e instanceof SecurityException) return "无法访问此文件，请通过“打开 PDF”重新选择并授权。";
        if(e instanceof FileNotFoundException) return "文件已移动、删除或无法访问，请重新选择。";
        String m=e.getMessage(); return "操作未完成："+(m==null?"文件可能损坏或格式不受支持。":m);
    }
    private void error(String text) { if(!isFinishing()) dialog().setTitle("提示").setMessage(text).setPositiveButton("知道了",null).show(); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
    private static void copy(InputStream input,OutputStream output) throws IOException {
        if(input==null||output==null) throw new IOException("文件提供方未返回可用的数据流");
        byte[] b=new byte[65536]; int n; while((n=input.read(b))!=-1) output.write(b,0,n); output.flush();
    }
    private void offerImport(Uri uri) {
        dialog().setTitle("从其他应用打开 PDF")
            .setMessage("保存到纸阅后，微信或 QQ 的临时分享权限失效也能继续阅读和编辑，并可直接覆盖这份本地副本。")
            .setPositiveButton("保存到纸阅并打开",(d,w)->importLocal(uri))
            .setNeutralButton("仅本次打开",(d,w)->openUri(uri,null)).setNegativeButton("取消",null).show();
    }
    private void importLocal(Uri uri) {
        final String display=fileName(uri);
        task("正在保存到纸阅…",()->{
            File directory=new File(getFilesDir(),"imports");
            if(!directory.exists()&&!directory.mkdirs()) throw new IOException("无法创建本地文档目录");
            String safe=display.replaceAll("[\\\\/:*?\"<>|]","_");
            if(!safe.toLowerCase(Locale.ROOT).endsWith(".pdf")) safe+=".pdf";
            File target=new File(directory,System.currentTimeMillis()+"-"+safe);
            try(InputStream input=getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(target)) { copy(input,output); }
            return target;
        },file->{ toast("已保存到纸阅"); openUri(Uri.fromFile(file),null); });
    }
    private void openUri(Uri uri,String password) {
        if(busy) return; capturePosition();
        if(returnToContinuous) { continuous=true; returnToContinuous=false; }
        String nextDraft=drafts.id(positionKey(uri.toString()));
        if(password==null&&drafts.dirty(nextDraft)) { restoreDraft(nextDraft); return; }
        if(continuousView!=null) { continuousView.dispose(); continuousView=null; }
        task("正在打开 PDF…",()->{
            File stage=new File(getCacheDir(),"incoming-"+nextDraft+".pdf");
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(stage)) { copy(in,out); }
            return prepare(stage,password,drafts.pdf(nextDraft));
        },result->{
            if(result==-1) { password(uri); return; }
            draftId=nextDraft; source=drafts.pdf(draftId); getPreferences(0).edit().putString("active_draft",draftId).putString("last_draft",draftId).apply();
            name=fileName(uri); sourceUri=uri.toString(); saveStatus="已打开"; opened(result); remember(); persist();
        });
    }
    private int prepare(File stage,String password,File target) throws IOException {
        boolean permission;
        try(PDDocument doc=PDDocument.load(stage,password==null?"":password)) {
            if(doc.getNumberOfPages()<1) throw new IOException("此 PDF 没有页面");
            permission=doc.getCurrentAccessPermission().canModify();
            if(doc.isEncrypted()) {
                // A private decrypted working copy lets the system renderer read password-protected files.
                File unlocked=new File(getCacheDir(),"unlocked-"+target.getName());
                doc.setAllSecurityToBeRemoved(true); doc.save(unlocked); stage=unlocked;
            }
        } catch(InvalidPasswordException e) { return -1; }
        // Verify rendering before replacing the previous recoverable session.
        try(PdfRenderer check=new PdfRenderer(ParcelFileDescriptor.open(stage,ParcelFileDescriptor.MODE_READ_ONLY))) {
            if(check.getPageCount()<1) throw new IOException("无法读取 PDF 页面");
        }
        closeRenderer();
        AtomicFile atomic=new AtomicFile(target); FileOutputStream output=null;
        try(InputStream in=new FileInputStream(stage)) { output=atomic.startWrite(); copy(in,output); atomic.finishWrite(output); }
        catch(IOException e) { if(output!=null) atomic.failWrite(output); throw e; }
        renderer=new PdfRenderer(ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_ONLY));
        canModify=permission;
        stage.delete();
        return renderer.getPageCount();
    }
    private void password(Uri uri) {
        EditText input=new EditText(dialogContext()); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("PDF 打开密码");
        dialog().setTitle("此 PDF 需要密码").setMessage("请输入密码；若刚才输入过，请检查是否正确。")
                .setView(input).setPositiveButton("解锁",(d,w)->openUri(uri,input.getText().toString())).setNegativeButton("取消",null).show();
    }
    private void opened(int count) {
        sheets.clear(); for(int i=0;i<count;i++) sheets.add(new PdfEngine.Sheet(i));
        current=0; readingFraction=0; loadPosition(); mode=0; readerChromeVisible=true; undo.clear(); redo.clear(); savedModel=PdfEngine.encode(sheets); viewer();
    }
    private void closeRenderer() { if(renderer!=null) { renderer.close(); renderer=null; } }
    private void viewer() {
        viewing=true; settingsPage=false; settingsFromViewer=false; base();
        bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8),dp(4),dp(8),dp(4));
        TextView back=iconButton("‹","返回",()->confirmLeave(this::home)); bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titles=vertical(); titles.setPadding(dp(12),0,dp(8),0);
        title=label(name,16,INK,true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); titles.addView(title);
        subtitle=label("",11,MUTED,false); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); titles.addView(subtitle); bar.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        saveButton=button("保存",this::saveDialog,true); saveButton.setPadding(dp(4),0,dp(4),0); saveButton.setSingleLine(true); bar.addView(saveButton,new LinearLayout.LayoutParams(dp(60),dp(44)));
        TextView more=iconButton("⋮","文档工具",this::more); LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(46),dp(44)); mp.leftMargin=dp(6); bar.addView(more,mp);
        TextView viewerSettings=iconButton("⚙","设置",this::openSettings);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(46),dp(44)); sp.leftMargin=dp(5); bar.addView(viewerSettings,sp);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        hint=label("",12,PURPLE,false); hint.setGravity(Gravity.CENTER); hint.setPadding(dp(8),dp(6),dp(8),dp(6)); root.addView(hint);
        content=new FrameLayout(this); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        pageView=new PageCanvas(this,new PageCanvas.Listener() {
            public void mark(PdfEngine.Mark mark) { if(!busy) { checkpoint(); sheets.get(current).marks.add(mark); changed(); } }
            public void text(float x,float y) { addText(x,y); }
            public void turn(int delta) { go(current+delta); }
            public void erase(int index) { eraseMark(index); }
            public void select(int index) { selectMark(index); }
        });
        pageView.night(night);
        pageView.setOnClickListener(v->toggleReaderChrome());
        if(continuous) {
            continuousView=new ContinuousView(this,renderer,sheets,worker,night,fitContent,current,readingFraction,page->{ if(!busy&&!settingsPage&&continuousView!=null) {current=page; readingFraction=continuousView.pageFraction(); update(); persist();} });
            continuousView.setOnClickListener(v->toggleReaderChrome());
            content.addView(continuousView,new FrameLayout.LayoutParams(-1,-1)); pageView.setVisibility(View.GONE);
        } else {
            continuousView=null; content.addView(pageView,new FrameLayout.LayoutParams(-1,-1));
        }
        LinearLayout nav=new LinearLayout(this); readerNav=nav; nav.setGravity(Gravity.CENTER); nav.setPadding(dp(14),dp(4),dp(14),dp(4));
        TextView prev=button("‹ 上一页",()->go(current-1),false); nav.addView(prev);
        pager=label("",14,INK,true); pager.setGravity(Gravity.CENTER); pager.setMinHeight(dp(48)); pager.setOnClickListener(v->{if(!busy) jump();});
        nav.addView(pager,new LinearLayout.LayoutParams(0,dp(48),1)); nav.addView(button("下一页 ›",()->go(current+1),false)); root.addView(nav);
        HorizontalScrollView scroller=new HorizontalScrollView(this); readerTools=scroller; scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tools=new LinearLayout(this); tools.setPadding(dp(8),dp(3),dp(8),dp(5)); scroller.addView(tools);
        if(!continuous&&returnToContinuous) {
            TextView backToScroll=compactButton("↩ 返回滚动",this::returnToContinuous); LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(dp(86),dp(38)); backParams.rightMargin=dp(2); tools.addView(backToScroll,backParams);
        }
        modes.clear(); String[] labels=continuous?new String[]{"☝ 阅读"}:new String[]{"☝ 阅读","✎ 画笔","▧ 荧光","T 文字","⌫ 擦除","▣ 选择"};
        for(int i=0;i<labels.length;i++) { final int index=i; TextView v=compactButton(labels[i],()->setMode(index)); modes.add(v);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(66),dp(38)); p.setMargins(dp(2),0,dp(2),0); tools.addView(v,p); }
        if(continuous) {
            TextView edit=compactButton("✎ 编辑本页",this::editCurrentPage); tools.addView(edit,new LinearLayout.LayoutParams(dp(86),dp(38)));
        }
        TextView undoButton=compactButton("↶ 撤销",this::undo); LinearLayout.LayoutParams undoParams=new LinearLayout.LayoutParams(dp(68),dp(38)); undoParams.leftMargin=dp(2); tools.addView(undoButton,undoParams);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,dp(46))); setMode(continuous?0:mode); applyReaderChrome(); render();
    }
    private TextView compactButton(String text,Runnable action) {
        TextView v=label(text,12,PURPLE,true); v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4),0,dp(4),0); v.setBackground(shape(0xffEDEAF9,10));
        v.setMinHeight(dp(38)); v.setFocusable(true); v.setOnClickListener(view->{ if(!busy) action.run(); });
        return v;
    }
    private void setMode(int next) {
        if(continuous&&next!=0) { editCurrentPage(); return; }
        if(next!=0&&!canModify) { error("作者已限制此 PDF 的修改权限，可以继续阅读。"); return; }
        mode=next; pageView.mode(mode);
        String[] help={"轻触隐藏 / 显示工具栏 · 双指缩放 · 左右翻页","在页面上手写标记 · 双指缩放或移动","拖出矩形标记区域 · 双指缩放或移动","点击页面放置文字 · 支持中文、多行输入","点击已有批注即可删除","点击批注后可编辑、换色或删除"}; hint.setText(continuous?"轻触隐藏 / 显示工具栏 · 上下滚动 · 可直接编辑当前页":help[mode]);
        for(int i=0;i<modes.size();i++) { modes.get(i).setTextColor(i==mode?Color.WHITE:(night?0xffCEC2FF:PURPLE)); modes.get(i).setBackground(shape(i==mode?PURPLE:0xffEDEAF9,10)); }
    }
    private void editCurrentPage() {
        if(!continuous||!editable()) return;
        capturePosition(); returnContinuousFraction=readingFraction; returnToContinuous=true;
        continuous=false; mode=PdfEngine.INK; viewer();
    }
    private void returnToContinuous() {
        if(!returnToContinuous) return;
        continuous=true; returnToContinuous=false; readingFraction=returnContinuousFraction; mode=0; viewer();
    }
    private void render() {
        if(renderer==null||sheets.isEmpty()||!viewing) return;
        if(continuous) { update(); return; }
        final int generation=++renderGeneration; final PdfEngine.Sheet sheet=sheets.get(current);
        pageView.setEnabled(false); update();
        worker.execute(()->{
            try {
                Bitmap b=PdfEngine.render(renderer,sheet,1800);
                runOnUiThread(()->{
                    if(destroyed||generation!=renderGeneration||!viewing) { b.recycle(); return; }
                    Bitmap old=displayed; displayed=b; pageView.setPage(b,sheet); pageView.setEnabled(true);
                    if(old!=null&&!old.isRecycled()) old.recycle();
                });
            } catch(Exception | OutOfMemoryError e) { runOnUiThread(()->{ if(!destroyed&&generation==renderGeneration) error(friendly(e)); }); }
        });
    }
    private void go(int index) { if(busy||index<0||index>=sheets.size()) return; current=index; readingFraction=0; if(continuous&&continuousView!=null) continuousView.jumpToPage(index); else render(); persist(); }
    private boolean dirty() { return !savedModel.equals(PdfEngine.encode(sheets)); }
    private void update() {
        if(!viewing) return; pager.setText((current+1)+" / "+sheets.size()+"  ⌄");
        boolean operationStatus=saveStatus.startsWith("正在")||saveStatus.contains("失败")||saveStatus.contains("未完成")||saveStatus.contains("已取消");
        String status=operationStatus?saveStatus:(dirty()?"编辑中 · 草稿已自动保存":saveStatus+" · "+sheets.size()+" 页");
        subtitle.setText(status+(canModify?"":" · 只读"));
        saveButton.setText(dirty()?"保存 •":"保存");
    }
    private void checkpoint() {
        undo.push(PdfEngine.encode(sheets)); if(undo.size()>30) undo.removeLast(); redo.clear();
    }
    private void changed() { saveStatus="编辑中 · 草稿已自动保存"; if(continuousView!=null) continuousView.invalidate(); else pageView.refresh(sheets.get(current)); update(); persist(); }
    private void undo() {
        if(undo.isEmpty()) { toast("没有可撤销的修改"); return; }
        redo.push(PdfEngine.encode(sheets)); applyState(undo.pop());
    }
    private void redo() {
        if(redo.isEmpty()) { toast("没有可重做的修改"); return; }
        undo.push(PdfEngine.encode(sheets)); applyState(redo.pop());
    }
    private void applyState(String json) {
        try { sheets=PdfEngine.decode(json); current=Math.min(current,sheets.size()-1); saveStatus="编辑中 · 草稿已自动保存"; if(continuous) reloadContinuous(); else render(); persist(); }
        catch(JSONException e) { error(friendly(e)); }
    }
    private void reloadContinuous() {
        if(!continuous||content==null||renderer==null||sheets.isEmpty()) return;
        if(continuousView!=null) continuousView.dispose();
        content.removeAllViews();
        continuousView=new ContinuousView(this,renderer,sheets,worker,night,fitContent,current,readingFraction,page->{ if(!busy&&!settingsPage&&continuousView!=null) {current=page; readingFraction=continuousView.pageFraction(); update(); persist();} });
        continuousView.setOnClickListener(v->toggleReaderChrome());
        content.addView(continuousView,new FrameLayout.LayoutParams(-1,-1));
        if(pageView!=null) pageView.setVisibility(View.GONE);
        update();
    }
    private void addText(float x,float y) {
        if(busy) return;
        LinearLayout box=vertical(); box.setPadding(dp(20),dp(4),dp(20),0);
        EditText input=new EditText(dialogContext()); input.setHint("输入批注文字"); input.setMinLines(2); input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE); box.addView(input);
        TextView sizeLabel=label("字号：18",13,MUTED,false); box.addView(sizeLabel);
        SeekBar size=new SeekBar(this); size.setMax(36); size.setProgress(8); box.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} public void onProgressChanged(SeekBar b,int n,boolean user){sizeLabel.setText("字号："+(n+10));} });
        AlertDialog dialog=dialog().setTitle("添加文字").setView(box).setPositiveButton("添加",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String text=input.getText().toString().trim(); if(text.isEmpty()) { input.setError("请输入文字"); return; }
            PdfEngine.Mark m=new PdfEngine.Mark(); m.type=PdfEngine.TEXT; m.color=INK; m.text=text;
            m.width=(size.getProgress()+10)/595f; m.points.add(new PointF(x,y)); checkpoint(); sheets.get(current).marks.add(m); changed(); dialog.dismiss();
        })); dialog.show();
    }
    private void eraseMark(int index) {
        if(index<0) { toast("这里没有批注"); return; }
        checkpoint(); sheets.get(current).marks.remove(index); changed(); toast("批注已擦除");
    }
    private void selectMark(int index) {
        if(index<0) { toast("这里没有批注"); return; }
        pageView.selected(index); PdfEngine.Mark mark=sheets.get(current).marks.get(index);
        ArrayList<String> actions=new ArrayList<>();
        if(mark.type==PdfEngine.TEXT) actions.add("编辑文字");
        actions.add("改为紫色"); actions.add("改为红色"); actions.add("删除批注"); actions.add("取消选择");
        dialog().setTitle("已选择批注").setItems(actions.toArray(new String[0]),(d,which)->{
            String action=actions.get(which);
            if("编辑文字".equals(action)) { editTextMark(index); return; }
            if("删除批注".equals(action)) { checkpoint(); sheets.get(current).marks.remove(index); pageView.selected(-1); changed(); return; }
            if("改为紫色".equals(action)||"改为红色".equals(action)) {
                checkpoint(); int color="改为紫色".equals(action)?0xff6253C8:0xffD14B5A;
                if(mark.type==PdfEngine.HIGHLIGHT) color=(color&0x00ffffff)|0x66000000;
                mark.color=color; changed(); pageView.selected(index); return;
            }
            pageView.selected(-1);
        }).setOnCancelListener(d->pageView.selected(-1)).show();
    }
    private void editTextMark(int index) {
        if(index<0||index>=sheets.get(current).marks.size()) return;
        PdfEngine.Mark mark=sheets.get(current).marks.get(index); EditText input=new EditText(dialogContext());
        input.setText(mark.text); input.setSelection(input.length()); input.setMinLines(2); input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog edit=dialog().setTitle("编辑批注文字").setView(input).setPositiveButton("保存",null).setNegativeButton("取消",(d,w)->pageView.selected(-1)).create();
        edit.setOnShowListener(d->edit.getButton(-1).setOnClickListener(v->{
            String text=input.getText().toString().trim(); if(text.isEmpty()) { input.setError("请输入文字"); return; }
            checkpoint(); mark.text=text; changed(); pageView.selected(index); edit.dismiss();
        })); edit.setOnCancelListener(d->pageView.selected(-1)); edit.show();
    }
    private void jump() {
        EditText input=new EditText(dialogContext()); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setHint("1 – "+sheets.size());
        dialog().setTitle("跳转到页码").setView(input).setPositiveButton("前往",(d,w)->{
            try { int n=Integer.parseInt(input.getText().toString()); if(n<1||n>sheets.size()) toast("请输入有效页码"); else go(n-1); }
            catch(NumberFormatException e) { toast("请输入页码"); }
        }).setNegativeButton("取消",null).show();
    }
    private void more() {
        capturePosition();
        String[] items={"打开其他 PDF","搜索文字","页面管理","顺时针旋转本页","删除本页","重做","分享 PDF",night?"切换日间阅读":"切换夜间阅读",continuous?"切换单页左右翻页":"切换连续滚动阅读","使用说明"};
        dialog().setTitle("文档工具").setItems(items,(d,n)->{
            switch(n) {
                case 0: confirmLeave(this::pick); break;
                case 1: search(); break;
                case 2: pages(); break;
                case 3: if(editable()) { checkpoint(); PdfEngine.rotate(sheets.get(current)); saveStatus="编辑中 · 草稿已自动保存"; if(continuous) reloadContinuous(); else render(); persist(); } break;
                case 4: deletePage(); break;
                case 5: redo(); break;
                case 6: export(true); break;
                case 7: capturePosition(); night=!night; getPreferences(0).edit().putBoolean("night",night).apply(); viewer(); break;
                case 8: capturePosition(); continuous=!continuous; returnToContinuous=false; getPreferences(0).edit().putBoolean("continuous",continuous).apply(); viewer(); break;
                case 9: help(); break;
            }
        }).show();
    }
    private boolean editable() { if(canModify) return true; error("此 PDF 的修改权限受限。"); return false; }
    private void deletePage() {
        if(!editable()) return;
        if(sheets.size()==1) { toast("至少需要保留一页"); return; }
        dialog().setTitle("删除第 "+(current+1)+" 页？").setMessage("只影响编辑副本，可用“撤销”恢复。")
            .setPositiveButton("删除",(d,w)->{checkpoint(); sheets.remove(current); current=Math.min(current,sheets.size()-1); saveStatus="编辑中 · 草稿已自动保存"; if(continuous) reloadContinuous(); else render(); persist();}).setNegativeButton("取消",null).show();
    }
    private void pages() {
        String[] list=new String[sheets.size()];
        for(int i=0;i<list.length;i++) list[i]="第 "+(i+1)+" 页"+(i==current?"  ·  当前页":"")+(!sheets.get(i).marks.isEmpty()?"  ·  有批注":"");
        dialog().setTitle("页面管理").setItems(list,(d,n)->{
            go(n);
            dialog().setTitle("第 "+(n+1)+" 页").setItems(new String[]{"阅读这一页","向前移动一页","向后移动一页"},(dialog,action)->{
                if(action==0||!editable()) return; int to=action==1?n-1:n+1;
                if(to<0||to>=sheets.size()) { toast("已到文档边界"); return; }
                checkpoint(); Collections.swap(sheets,n,to); current=to; saveStatus="编辑中 · 草稿已自动保存"; if(continuous) reloadContinuous(); else render(); persist();
            }).show();
        }).setNegativeButton("关闭",null).show();
    }
    private void search() {
        EditText input=new EditText(dialogContext()); input.setSingleLine(true); input.setHint("输入关键词");
        dialog().setTitle("搜索文档文字").setMessage("扫描图片中的文字暂不支持识别。")
            .setView(input).setPositiveButton("搜索",(d,w)->{
                String q=input.getText().toString().trim(); if(q.isEmpty()) return;
                task("正在搜索…",()->PdfEngine.search(source,sheets,q),found->{
                    if(found.isEmpty()) { toast("未找到匹配文字"); return; }
                    String[] hits=new String[found.size()]; for(int i=0;i<hits.length;i++) hits[i]="第 "+(found.get(i)+1)+" 页";
                    dialog().setTitle("找到 "+hits.length+" 个匹配页面").setItems(hits,(dialog,n)->go(found.get(n))).setNegativeButton("关闭",null).show();
                });
            }).setNegativeButton("取消",null).show();
    }
    private void help() {
        dialog().setTitle("纸阅 PDF · 使用说明 · v"+BuildConfig.VERSION_NAME)
            .setMessage("阅读：阅读模式轻触页面隐藏或显示工具栏，隐藏时按返回先呼出工具栏；双击仍用于缩放。屏幕方向跟随系统自动旋转设置，横屏单页适合页宽，可上下拖动阅读长页，左右安全边距避开导航栏和挖孔。连续滚动时可直接点“编辑本页”，完成后返回原滚动位置。\n\n修改：画笔手写、矩形荧光、中文文字、点按擦除；“选择”可编辑文字、换色或删除批注。支持页面旋转、删除、调整顺序，以及撤销和重做。\n\n导入：从微信、QQ 或其他应用打开时，可一键“保存到纸阅并打开”。本地副本不依赖临时分享权限，并可直接覆盖保存。\n\n保存：标题下方会显示正在保存、已写入原文件、已另存副本或写入失败。失败时编辑草稿仍保留。覆盖原文件前会备份并校验写入；批注作为页面内容写入，其他 PDF 阅读器可查看。\n\n草稿：每份文档单独自动保存编辑模型和阅读位置，可以直接切换文档；最近列表会标出尚未导出的编辑草稿。清空最近列表不会删除草稿。\n\n边界：不支持直接改写原有段落、OCR 或交互表单。加密 PDF 需输入合法密码；另存副本不保留加密。\n\n隐私：无需网络权限，不上传文件。\n\n开源组件：PDFBox-Android 2.0.27.0（Apache 2.0）、Apache PDFBox / FontBox、Bouncy Castle（MIT）。")
            .setPositiveButton("知道了",null).show();
    }
    private void saveDialog() {
        dialog().setTitle("保存 PDF").setMessage("覆盖保存会将修改写回当前本地文件；另存为可保留原文件。")
            .setPositiveButton("覆盖原文件",(d,w)->{ if(editable()) export(false,true); })
            .setNeutralButton("另存为 PDF",(d,w)->export(false)).setNegativeButton("取消",null).show();
    }
    private void export(boolean share) { export(share,false); }
    private void export(boolean share,boolean overwrite) {
        final String snapshot=PdfEngine.encode(sheets);
        boolean edits=sheets.size()!=renderer.getPageCount();
        for(int i=0;i<sheets.size();i++) { PdfEngine.Sheet s=sheets.get(i); if(s.original!=i||s.rotation!=0||!s.marks.isEmpty()) edits=true; }
        final boolean hasEdits=edits;
        if(!share) { saveStatus="正在准备保存…"; update(); persist(); }
        task("正在生成 PDF…",()->{
            File directory=new File(getCacheDir(),"shared"); if(!directory.exists()&&!directory.mkdirs()) throw new IOException("无法创建临时目录");
            File file=new File(directory,"paper-"+System.currentTimeMillis()+".pdf");
            if(!canModify||!hasEdits) { try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(file)) {copy(in,out);} }
            else PdfEngine.export(source,PdfEngine.decode(snapshot),file);
            try(PdfRenderer check=new PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))) {
                if(check.getPageCount()!=sheets.size()) throw new IOException("导出验证失败：页数不匹配");
            }
            return file;
        },file->{
            if(overwrite) { pendingOverwrite=file; requestOverwrite(); return; }
            if(share) {
                Uri uri=Uri.parse("content://cn.paperpdf.reader.share/"+file.getName());
                Intent intent=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("PDF",uri)); startActivity(Intent.createChooser(intent,"分享 PDF"));
            } else {
                pendingExport=file;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,name.replaceFirst("(?i)\\.pdf$","")+"-已编辑.pdf");
                try { startActivityForResult(intent,SAVE); } catch(ActivityNotFoundException e) { pendingExport=null; saveStatus="另存失败 · 草稿仍在"; update(); persist(); error("设备没有可用的系统文件保存器。"); }
            }
        },()->{ if(!share) { saveStatus="保存失败 · 草稿仍在"; update(); persist(); } });
    }
    private void requestOverwrite() {
        if(sourceUri.isEmpty()) { pendingOverwrite=null; saveStatus="覆盖失败 · 草稿仍在"; update(); persist(); error("当前文档没有原文件位置，请另存为 PDF。"); return; }
        Uri uri=Uri.parse(sourceUri);
        boolean writable="file".equals(uri.getScheme())&&new File(uri.getPath()).canWrite();
        writable|=checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(writable) { writeOriginal(uri); return; }
        saveStatus="正在等待原文件授权…"; update(); persist();
        dialog().setTitle("需要原文件写入授权")
            .setMessage("请在系统文件选择器中重新选择原 PDF：“"+name+"”。只读的微信 / QQ 分享文件可先保存到本地，再用纸阅打开。")
            .setPositiveButton("选择原文件",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if(Build.VERSION.SDK_INT>=26) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,uri);
                try {startActivityForResult(intent,WRITE_ORIGINAL);} catch(ActivityNotFoundException e) {error("未找到系统文件选择器，请另存为 PDF。");}
            }).setNegativeButton("取消",(d,w)->{pendingOverwrite=null;saveStatus="保存已取消 · 草稿仍在";update();persist();}).show();
    }
    private void writeOriginal(Uri uri) {
        if(pendingOverwrite==null) return;
        File edited=pendingOverwrite; final String snapshot=PdfEngine.encode(sheets);
        saveStatus="正在写入原文件…"; update(); persist();
        task("正在覆盖并校验原文件…",()->{
            File backup=new File(getFilesDir(),"original-backup-"+System.currentTimeMillis()+".pdf");
            OriginalFileWriter.replace(getContentResolver(),uri,edited,backup); return true;
        },ok->{savedModel=snapshot; pendingOverwrite=null; saveStatus="已写入原文件"; update(); persist(); toast("已写入原文件，草稿与文件一致");},
        ()->{saveStatus="写入失败 · 草稿仍在";update();persist();});
    }
    private void keepPermission(Uri uri,Intent data) {
        try {
            if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(SecurityException ignored) { }
    }
    private String positionKey(String value) {
        try {Uri uri=Uri.parse(value); if(DocumentsContract.isDocumentUri(this,uri)) return uri.getAuthority()+":"+DocumentsContract.getDocumentId(uri);}
        catch(Exception ignored) { }
        return value;
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(busy) { root.postDelayed(()->{if(!destroyed) onActivityResult(request,result,data);},150); return; }
        if(result!=RESULT_OK||data==null||data.getData()==null) {
            if(request==SAVE&&pendingExport!=null) { pendingExport=null; saveStatus=dirty()?"编辑中 · 草稿已自动保存":"已打开"; update(); persist(); }
            if(request==WRITE_ORIGINAL&&pendingOverwrite!=null) { pendingOverwrite=null; saveStatus="保存已取消 · 草稿仍在"; update(); persist(); }
            return;
        }
        Uri uri=data.getData();
        if(request==WRITE_ORIGINAL) {
            keepPermission(uri,data);
            if(!positionKey(sourceUri).equals(positionKey(uri.toString()))) { error("选择的不是当前原文件，未覆盖任何文件。请重新选择原文件，或使用另存为 PDF。"); return; }
            sourceUri=uri.toString(); writeOriginal(uri);
        } else if(request==FOLDER) {
            keepPermission(uri,data); saveFolder(uri);
        } else if(request==OPEN) {
            keepPermission(uri,data); openUri(uri,null);
        } else if(request==SAVE&&pendingExport!=null) {
            final File file=pendingExport;
            task("正在保存文件…",()->{ try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri,"wt")) { copy(in,out); } return true; },ok->{
                savedModel=PdfEngine.encode(sheets); pendingExport=null; saveStatus="已另存副本："+fileName(uri); update(); persist(); toast("已另存 PDF，草稿与副本一致");
            },()->{saveStatus="另存失败 · 草稿仍在";update();persist();});
        }
    }
    private void confirmLeave(Runnable action) {
        if(busy) return;
        capturePosition(); action.run();
    }
    private void capturePosition() {
        if(busy||sheets.isEmpty()) return;
        if(continuousView!=null&&!settingsPage) {current=continuousView.currentPage();readingFraction=continuousView.pageFraction();}
        persist();
    }
    private void loadPosition() {
        try {
            JSONObject positions=new JSONObject(getPreferences(0).getString("positions","{}"));
            JSONObject saved=positions.optJSONObject(positionKey(sourceUri));
            if(saved!=null) { current=Math.max(0,Math.min(sheets.size()-1,saved.optInt("page"))); readingFraction=(float)saved.optDouble("fraction",0); }
        } catch(JSONException ignored) { }
    }
    private void persist() {
        if(sheets.isEmpty()||draftId.isEmpty()) return;
        try {
            if(!sourceUri.isEmpty()) {
                JSONObject positions=new JSONObject(getPreferences(0).getString("positions","{}"));
                positions.put(positionKey(sourceUri),new JSONObject().put("page",current).put("fraction",readingFraction));
                getPreferences(0).edit().putString("positions",positions.toString()).apply();
            }
            JSONObject json=new JSONObject().put("fraction",readingFraction).put("name",name).put("uri",sourceUri).put("pages",PdfEngine.encode(sheets)).put("page",current)
                .put("saved",savedModel).put("modify",canModify).put("status",saveStatus);
            drafts.write(draftId,json);
            getPreferences(0).edit().putString("active_draft",draftId).putString("last_draft",draftId).apply();
        } catch(Exception e) { toast("草稿保存失败，请及时另存 PDF"); }
    }
    private void restore() {
        String last=getPreferences(0).getString("last_draft","");
        if(drafts.exists(last)) { restoreDraft(last); return; }
        File legacyState=new File(getFilesDir(),"session.json"),legacyPdf=new File(getFilesDir(),"current.pdf");
        if(!legacyState.isFile()||!legacyPdf.isFile()) { error("没有可恢复的阅读记录。"); return; }
        task("正在恢复文档…",()->{
            String json;
            try(InputStream in=new AtomicFile(legacyState).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()) { copy(in,out); json=out.toString("UTF-8"); }
            JSONObject state=new JSONObject(json); String id=drafts.id(positionKey(state.optString("uri","legacy")));
            File target=drafts.pdf(id); try(InputStream in=new FileInputStream(legacyPdf);OutputStream out=new FileOutputStream(target)) { copy(in,out); }
            state.put("status","已从 1.4.0 恢复"); drafts.write(id,state); state.put("_draft",id); return state;
        },state->{
            restoreDraft(state.optString("_draft"));
        });
    }
    private void restoreDraft(String id) {
        if(!drafts.exists(id)) { error("草稿文件已丢失，请重新打开原 PDF。"); return; }
        task("正在恢复文档…",()->{
            JSONObject state=drafts.read(id); File pdf=drafts.pdf(id);
            try(PdfRenderer check=new PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY))) {
                if(check.getPageCount()<1) throw new IOException("草稿 PDF 没有页面");
            }
            closeRenderer(); renderer=new PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)); return state;
        },state->{
            try {
                draftId=id; source=drafts.pdf(id); name=state.getString("name"); sourceUri=state.optString("uri"); sheets=PdfEngine.decode(state.getString("pages"));
                current=Math.max(0,Math.min(state.optInt("page"),sheets.size()-1)); readingFraction=(float)state.optDouble("fraction",0); savedModel=state.getString("saved"); canModify=state.optBoolean("modify",true);
                saveStatus=state.optString("status","已恢复草稿"); if(saveStatus.startsWith("正在")) saveStatus="上次保存未完成 · 草稿仍在";
                continuous=getPreferences(0).getBoolean("continuous",false); returnToContinuous=false;
                getPreferences(0).edit().putString("active_draft",id).putString("last_draft",id).apply(); undo.clear(); redo.clear(); mode=0; remember(); viewer();
            } catch(JSONException e) { error("草稿无法恢复，请重新打开 PDF。"); }
        });
    }
    @Override public void onBackPressed() { if(busy) return; if(settingsPage) { if(settingsFromViewer&&renderer!=null) viewer(); else home(); return; } if(viewing&&!readerChromeVisible) {readerChromeVisible=true;applyReaderChrome();return;} if(viewing) confirmLeave(this::home); else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putBoolean("viewing",viewing);
        state.putBoolean("readerChromeVisible",readerChromeVisible);
        if(pendingExport!=null) state.putString("pending",pendingExport.getAbsolutePath());
    }
    @Override protected void onPause() { capturePosition(); super.onPause(); }
    @Override protected void onDestroy() {
        if(continuousView!=null) continuousView.dispose();
        destroyed=true; renderGeneration++; if(progress!=null) progress.dismiss();
        worker.execute(this::closeRenderer); worker.shutdown(); super.onDestroy();
    }
}
