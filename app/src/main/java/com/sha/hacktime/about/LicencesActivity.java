package com.sha.hacktime.about;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import com.sha.hacktime.R;

public class LicencesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.generic_layout);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new LicencesFragment(), "LicencesFragment")
                .commit();
    }
}