/* SPDX-License-Identifier: Apache-2.0 */
package com.aura.island;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** Privileged calibration UI; no accessibility, root, network, or app-content access. */
public final class IslandCalibrationActivity extends Activity {
    private final LinkedHashMap<IslandCameraConfig.Key,EditText> fields=new LinkedHashMap<>();
    private Switch auto;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(android.os.UserHandle.myUserId()!=android.os.UserHandle.USER_SYSTEM) {finish();return;}
        ScrollView scroll=new ScrollView(this);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        int p=Math.round(20*getResources().getDisplayMetrics().density);body.setPadding(p,p,p,p);
        scroll.addView(body);setContentView(scroll);
        TextView title=new TextView(this);title.setText(R.string.island_camera_title);title.setTextSize(22);body.addView(title);
        TextView help=new TextView(this);help.setText(R.string.island_camera_help);body.addView(help);
        auto=new Switch(this);auto.setText(R.string.island_camera_auto_label);body.addView(auto);
        for(IslandCameraConfig.Key key:IslandCameraConfig.KEYS) {
            TextView label=new TextView(this);label.setText(key.label);body.addView(label);
            EditText field=new EditText(this);field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
            body.addView(field);fields.put(key,field);
        }
        Button apply=new Button(this);apply.setText(R.string.island_camera_apply);body.addView(apply);
        apply.setOnClickListener(v->save());
        Button reset=new Button(this);reset.setText(R.string.island_camera_reset);body.addView(reset);
        reset.setOnClickListener(v->{Settings.Global.putString(getContentResolver(),IslandCameraConfig.SETTING,null);load();});
        load();
    }
    private void load() {
        JSONObject object=IslandCameraConfig.read(this);auto.setChecked(IslandCameraConfig.auto(this,object));
        fields.forEach((key,field)->field.setText(Float.toString(IslandCameraConfig.value(this,object,key.name))));
    }
    private void save() {
        JSONObject object=new JSONObject();
        try {
            object.put("version",1);object.put("auto_cutout",auto.isChecked());
            for(var entry:fields.entrySet()) {
                float value=Float.parseFloat(entry.getValue().getText().toString().trim());
                var key=entry.getKey();
                if(!Float.isFinite(value)||value<key.min||value>key.max)throw new IllegalArgumentException(key.name);
                if(key.name.equals("cutout_index")&&value!=Math.round(value))throw new IllegalArgumentException(key.name);
                object.put(key.name,value);
            }
            if(!Settings.Global.putString(getContentResolver(),IslandCameraConfig.SETTING,object.toString()))throw new IllegalStateException();
            Toast.makeText(this,R.string.island_camera_saved,Toast.LENGTH_SHORT).show();
        } catch(org.json.JSONException|IllegalArgumentException|IllegalStateException e) {
            Toast.makeText(this,R.string.island_camera_invalid,Toast.LENGTH_LONG).show();
        }
    }
}
