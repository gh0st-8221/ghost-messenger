package com.dx.ghostmessenger.ui.view.setup;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dx.ghostmessenger.DxApplication;
import com.dx.ghostmessenger.R;
import com.dx.ghostmessenger.db.DbHelper;
import com.dx.ghostmessenger.service.DxService;
import com.dx.ghostmessenger.ui.view.DxActivity;
import com.dx.ghostmessenger.ui.view.MainActivity;
import com.dx.ghostmessenger.ui.view.single_activity.AboutActivity;
import com.dx.ghostmessenger.ui.view.single_activity.DonateActivity;
import com.dx.ghostmessenger.ui.view.single_activity.LicenseActivity;
import com.dx.ghostmessenger.ui.view.single_activity.SimpleScannerActivity;
import com.dx.ghostmessenger.util.Utils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


import android.app.ProgressDialog;
import android.os.PowerManager;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import android.text.InputType;
import com.dx.ghostmessenger.DxApplication;
import com.dx.ghostmessenger.file.BackupHelper;
import com.dx.ghostmessenger.file.FileHelper;


/**
 * A simple {@link Fragment} subclass.
 * Use the {@link SetupSettingsFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class SetupSettingsFragment extends Fragment {

    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_SHOW_BACKUP = "showBackup";

    private boolean isInSetup;
    private boolean autoShowBackupRestore;
    final ActivityResultLauncher<Intent> mScanQrCode = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (result.getData() != null) {
                        String bridges = result.getData().getStringExtra("RESULT");
                        if (bridges != null) {
                            String[] lines = bridges.split(",");
                            for (String line : lines) {
                                DbHelper.saveBridge(line.trim().replace("'", "").replace("[","").replace("]",""), (DxApplication) requireActivity().getApplication());
                            }
                        }
                    }
                    updateBridgeList();
                }
            });
    final ActivityResultLauncher<String> mPermissionResult = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {
                if(result) {
                    Intent intent = new Intent(requireContext(), SimpleScannerActivity.class);
                    intent.putExtra("SCAN_MODE", "ADD_BRIDGE");
                    mScanQrCode.launch(intent);
                }
            });

    final ActivityResultLauncher<android.content.Intent> mPickBackupFile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    android.net.Uri backupUri = result.getData().getData();
                    if (backupUri != null) {
                        showRestorePasswordDialog(backupUri);
                    }
                }
            });

    // Launcher for choosing where to save the backup file (modern Android picker)
    private final ActivityResultLauncher<Intent> mSaveBackup = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    android.net.Uri saveUri = result.getData().getData();
                    if (saveUri != null) {
                        showBackupPasswordDialog(saveUri);
                    }
                }
            });

    public SetupSettingsFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param isInSetup boolean indicates whether the view is used within first time use setup or not.
     * @return A new instance of fragment SetupSettingsFragment.
     */
    public static SetupSettingsFragment newInstance(boolean isInSetup) {
        return newInstance(isInSetup, false);
    }

    public static SetupSettingsFragment newInstance(boolean isInSetup, boolean showBackupRestore) {
        SetupSettingsFragment fragment = new SetupSettingsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PARAM1, isInSetup);
        args.putBoolean(ARG_SHOW_BACKUP, showBackupRestore);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            isInSetup = getArguments().getBoolean(ARG_PARAM1);
            autoShowBackupRestore = getArguments().getBoolean(ARG_SHOW_BACKUP);
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        if (autoShowBackupRestore && !isInSetup) {
            autoShowBackupRestore = false;
            requireView().postDelayed(() -> showBackupRestoreDialog(), 300);
        }
    }

    @Override
    public void onDestroyView() {
        ((DxApplication)requireActivity().getApplication()).reloadSettings();
        super.onDestroyView();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final View rootView = inflater.inflate(R.layout.fragment_setup_settings, container, false);

        final LinearLayout bridgesLayout = rootView.findViewById(R.id.bridge_settings_layout);
        final Button done = rootView.findViewById(R.id.done);
        final SwitchMaterial bridgesSwitch = rootView.findViewById(R.id.switch_bridges);
        final SwitchMaterial unknownContactsSwitch = rootView.findViewById(R.id.switch_allow_unknown);
        final SwitchMaterial allowCalls = rootView.findViewById(R.id.switch_allow_calls);
        final SwitchMaterial allowFilesSwitch = rootView.findViewById(R.id.switch_allow_files);
        final TextInputEditText txtFileLimit = rootView.findViewById(R.id.txt_file_limit);
        final TextInputLayout layoutFileLimit = rootView.findViewById(R.id.txt_layout_file_limit);
        final TextInputEditText txtCheckAddress = rootView.findViewById(R.id.txt_check_address);
        final RecyclerView rvBridges = rootView.findViewById(R.id.rv_bridges);
        final Button addBridge = rootView.findViewById(R.id.btn_add_bridge);
        final Button scanBridge = rootView.findViewById(R.id.btn_scan_bridges);
        final SwitchMaterial enableSocks5Proxy = rootView.findViewById(R.id.switch_use_proxy);
        final TextInputLayout layoutProxy = rootView.findViewById(R.id.txt_layout_proxy);
        final TextInputEditText txtProxyAddress = rootView.findViewById(R.id.txt_proxy_address);
        final TextInputEditText txtProxyUsername = rootView.findViewById(R.id.txt_proxy_username);
        final TextInputEditText txtProxyPassword = rootView.findViewById(R.id.txt_proxy_password);
        final TextInputLayout layoutExclude = rootView.findViewById(R.id.txt_layout_exclude);
        final TextInputEditText txtExclude = rootView.findViewById(R.id.txt_exclude);
        final SwitchMaterial excludeUnknown = rootView.findViewById(R.id.switch_exclude_unknown);
        final SwitchMaterial strictExclude = rootView.findViewById(R.id.switch_strict_exclude);
        final TextView reset = rootView.findViewById(R.id.btn_reset);

        rootView.findViewById(R.id.fab_check_address_help).setOnClickListener(v -> Utils.showHelpAlert(requireContext(),getString(R.string.online_check_address_explain), getString(R.string.online_check_address)));

        rootView.findViewById(R.id.fab_reset_check_address).setOnClickListener(v -> txtCheckAddress.setText(((DxApplication)requireActivity().getApplication()).DEFAULT_SETTINGS[4].toString()));

        rootView.findViewById(R.id.btn_request_bridge).setOnClickListener(v -> Utils.showHelpAlert(requireContext(),getString(R.string.request_bridge_help), getString(R.string.request_bridge)));

rootView.findViewById(R.id.btn_backup).setOnClickListener(v -> showBackupRestoreDialog());

        reset.setOnClickListener(v -> {
            try{
                DbHelper.deleteSettings((DxApplication)requireActivity().getApplication());
            }catch (Exception ignored){}
            Object[] settings = ((DxApplication)requireActivity().getApplication()).DEFAULT_SETTINGS;
            bridgesSwitch.setChecked(((int)settings[0]>0));
            bridgesLayout.setVisibility((int)settings[0]>0?View.VISIBLE:View.GONE);
            unknownContactsSwitch.setChecked(((int)settings[1]>0));
            allowCalls.setChecked((int)settings[2]>0);
            allowFilesSwitch.setChecked((int)settings[3]>0);
            layoutFileLimit.setVisibility((int)settings[3]>0?View.VISIBLE:View.GONE);
            txtCheckAddress.setText((String) settings[4]);
            txtFileLimit.setText((String) settings[5]);
            enableSocks5Proxy.setChecked((int)settings[6]>0);
            layoutProxy.setVisibility((int)settings[6]>0?View.VISIBLE:View.GONE);
            txtProxyAddress.setText((String) settings[7]);
            txtProxyUsername.setText((String) settings[8]);
            txtProxyPassword.setText((String) settings[9]);
            txtExclude.setText((String) settings[10]);
            excludeUnknown.setChecked((int)settings[11]>0);
            strictExclude.setChecked((int)settings[12]>0);
        });

        //get default values
        if(isInSetup){
            bridgesSwitch.setChecked(((CreateUserActivity) requireActivity()).areBridgesEnabled());
            bridgesLayout.setVisibility(((CreateUserActivity) requireActivity()).areBridgesEnabled()?View.VISIBLE:View.GONE);
            allowCalls.setChecked(((CreateUserActivity) requireActivity()).isAcceptingCallsAllowed());
            unknownContactsSwitch.setChecked(((CreateUserActivity) requireActivity()).isAcceptingUnknownContactsEnabled());
            allowFilesSwitch.setChecked(((CreateUserActivity) requireActivity()).isReceivingFilesAllowed());
            layoutFileLimit.setVisibility(((CreateUserActivity) requireActivity()).isReceivingFilesAllowed()?View.VISIBLE:View.GONE);
            txtCheckAddress.setText(((CreateUserActivity) requireActivity()).getCheckAddress());
            txtFileLimit.setText(((CreateUserActivity) requireActivity()).getFileSizeLimit());
            enableSocks5Proxy.setChecked(((CreateUserActivity) requireActivity()).isEnableSocks5Proxy());
            layoutProxy.setVisibility(((CreateUserActivity) requireActivity()).isEnableSocks5Proxy()?View.VISIBLE:View.GONE);
            txtProxyAddress.setText(((CreateUserActivity) requireActivity()).getSocks5AddressAndPort());
            txtProxyUsername.setText(((CreateUserActivity) requireActivity()).getSocks5Username());
            txtProxyPassword.setText(((CreateUserActivity) requireActivity()).getSocks5Password());
            txtExclude.setText(((CreateUserActivity) requireActivity()).getExcludeText());
            excludeUnknown.setChecked(((CreateUserActivity) requireActivity()).isExcludeUnknown());
            strictExclude.setChecked(((CreateUserActivity) requireActivity()).isStrictExclude());
        }else{
            try{
                ((MaterialToolbar)requireActivity().findViewById(R.id.toolbar)).getMenu().clear();
                requireActivity().setTitle(R.string.action_settings);
                ((DxActivity)requireActivity()).setBackEnabled(true);
            }catch (Exception ignored){}

            //read from db in this case or fallback to defaults
            try{
                DxApplication appDb = (DxApplication)requireActivity().getApplication();
                Object[] settings = (appDb.getAccount() != null)
                        ? DbHelper.getSettingsList(appDb, true)
                        : appDb.DEFAULT_SETTINGS;
                if (settings == null || settings.length <= 5) {
                    Log.d("GHOSTMESSENGER","not right");
                    settings = ((DxApplication) requireActivity().getApplication()).DEFAULT_SETTINGS;
                }
                bridgesSwitch.setChecked(((int)settings[0]>0));
                bridgesLayout.setVisibility((int)settings[0]>0?View.VISIBLE:View.GONE);
                unknownContactsSwitch.setChecked(((int)settings[1]>0));
                allowCalls.setChecked((int)settings[2]>0);
                allowFilesSwitch.setChecked((int)settings[3]>0);
                layoutFileLimit.setVisibility((int)settings[3]>0?View.VISIBLE:View.GONE);
                txtCheckAddress.setText((String) settings[4]);
                txtFileLimit.setText((String) settings[5]);
                enableSocks5Proxy.setChecked((int)settings[6]>0);
                layoutProxy.setVisibility((int)settings[6]>0?View.VISIBLE:View.GONE);
                txtProxyAddress.setText((String) settings[7]);
                txtProxyUsername.setText((String) settings[8]);
                txtProxyPassword.setText((String) settings[9]);
                txtExclude.setText((String) settings[10]);
                excludeUnknown.setChecked((int)settings[11]>0);
                strictExclude.setChecked((int)settings[12]>0);
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        //make next button green
        done.setEnabled(true);
        done.setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.green_tor)
        ));

        //set about & license text view buttons to be visible
        rootView.findViewById(R.id.txt_other).setVisibility(View.VISIBLE);
        TextView changeAppName = rootView.findViewById(R.id.btn_change_app_name);
//        changeAppName.setVisibility(View.VISIBLE);
//        changeAppName.setOnClickListener(v -> {
//            CharSequence[] names = new CharSequence[]{"Ghost Messenger", "Securoo", "AM"};
//
//            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext(),R.style.AppAlertDialog);
//            builder.setTitle(R.string.select_app_name);
//            builder.setItems(names, (dialog, which) -> {
//                switch (which) {
//                    case 0:
//                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
//                        prefs.edit().putString("app-name","com.dx.ghostmessenger.ui.view.MainActivity").apply();
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), MainActivity.class),
//                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), "com.dx.ghostmessenger.ui.view.AM"),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(),  "com.dx.ghostmessenger.ui.view.Securoo"),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        Intent intent = new Intent();
//                        String packageName = requireContext().getPackageName();
//                        String alias = prefs.getString("app-name","com.dx.ghostmessenger.ui.view.MainActivity");
//                        ComponentName componentName = new ComponentName(packageName,
//                                Objects.requireNonNull(alias));
//                        intent.setComponent(componentName);
//                        requireActivity().finishAndRemoveTask();
//                        requireActivity().overridePendingTransition(R.anim.slide_from_left, R.anim.slide_to_right);
//                        try {
//                            Thread.sleep(500);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//                        startActivity(intent);
//                        break;
//                    case 1:
//                        SharedPreferences prefs2 = PreferenceManager.getDefaultSharedPreferences(requireContext());
//                        prefs2.edit().putString("app-name","com.dx.ghostmessenger.ui.view.Securoo").apply();
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), "com.dx.ghostmessenger.ui.view.Securoo"),
//                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), MainActivity.class),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(),  "com.dx.ghostmessenger.ui.view.AM"),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        intent = new Intent();
//                        packageName = requireContext().getPackageName();
//                        alias = prefs2.getString("app-name","com.dx.ghostmessenger.ui.view.MainActivity");
//                        componentName = new ComponentName(packageName,
//                                Objects.requireNonNull(alias));
//                        intent.setComponent(componentName);
//                        requireActivity().finishAndRemoveTask();
//                        requireActivity().overridePendingTransition(R.anim.slide_from_left, R.anim.slide_to_right);
//                        try {
//                            Thread.sleep(500);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//                        startActivity(intent);
//                        break;
//                    case 2:
//                        SharedPreferences prefs3 = PreferenceManager.getDefaultSharedPreferences(requireContext());
//                        prefs3.edit().putString("app-name","com.dx.ghostmessenger.ui.view.AM").apply();
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), "com.dx.ghostmessenger.ui.view.AM"),
//                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), MainActivity.class),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        requireActivity().getPackageManager().setComponentEnabledSetting(
//                                new ComponentName(requireActivity(), "com.dx.ghostmessenger.ui.view.Securoo"),
//                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//                        intent = new Intent();
//                        packageName = requireContext().getPackageName();
//                        alias = prefs3.getString("app-name","com.dx.ghostmessenger.ui.view.MainActivity");
//                        componentName = new ComponentName(packageName,
//                                Objects.requireNonNull(alias));
//                        intent.setComponent(componentName);
//                        requireActivity().finishAndRemoveTask();
//                        requireActivity().overridePendingTransition(R.anim.slide_from_left, R.anim.slide_to_right);
//                        try {
//                            Thread.sleep(500);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//                        startActivity(intent);
//                        break;
//                }
//                // refresh service notification
//                if(DxApplication.isServiceRunningInForeground(requireContext(), DxService.class)){
//                    ((DxApplication)requireActivity().getApplication()).updateServiceNotification();
//                }
//            });
//            builder.show();
//        });
        TextView changeTheme = rootView.findViewById(R.id.btn_change_theme);
        changeTheme.setVisibility(View.VISIBLE);
        changeTheme.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[]{getString(R.string.dark_theme), getString(R.string.light_theme)};
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext(),R.style.AppAlertDialog);
            builder.setTitle(R.string.action_change_theme);
            builder.setItems(names, (dialog, which) -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                Intent intent = new Intent();
                String packageName = requireContext().getPackageName();
                String alias = prefs.getString("app-name","com.dx.ghostmessenger.ui.view.MainActivity");
                ComponentName componentName = new ComponentName(packageName,
                        Objects.requireNonNull(alias));
                intent.setComponent(componentName);
                switch (which) {
                    case 0:
                        prefs.edit().putString("app-theme","dark").apply();
                        requireActivity().setTheme(R.style.AppTheme);
                        requireActivity().finishAndRemoveTask();
                        requireActivity().overridePendingTransition(R.anim.slide_from_left, R.anim.slide_to_right);
                        startActivity(intent);
                        break;
                    case 1:
                        SharedPreferences prefs2 = PreferenceManager.getDefaultSharedPreferences(requireContext());
                        prefs2.edit().putString("app-theme","light").apply();
                        requireActivity().setTheme(R.style.LightTheme);
                        requireActivity().finishAndRemoveTask();
                        requireActivity().overridePendingTransition(R.anim.slide_from_left, R.anim.slide_to_right);
                        startActivity(intent);
                        break;
                }
            });
            builder.show();
        });
        TextView donate = rootView.findViewById(R.id.btn_donate);
        donate.setVisibility(View.VISIBLE);
        donate.setOnClickListener((v)->{
            try{
                Intent intent = new Intent(getContext(), DonateActivity.class);
                if(getContext()!=null){
                    getContext().startActivity(intent);
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        });
        TextView license = rootView.findViewById(R.id.btn_license);
        license.setVisibility(View.VISIBLE);
        license.setOnClickListener((v)->{
            try{
                Intent intent = new Intent(getContext(), LicenseActivity.class);
                if(getContext()!=null){
                    getContext().startActivity(intent);
                }
            }catch (Exception ignored) {}
        });
        TextView about = rootView.findViewById(R.id.btn_about);
        about.setVisibility(View.VISIBLE);
        about.setOnClickListener((v)->{
            try{
                Intent intent = new Intent(getContext(), AboutActivity.class);
                if(getContext()!=null){
                    getContext().startActivity(intent);
                }
            }catch (Exception ignored) {}
        });

        //exclude Tor nodes related
        txtExclude.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                //accepted format is : xx,xx...

                String[] array = s.toString().split(",");
                for (String value : array) {
                    if (value.length() != 2 && value.length() != 0) {
                        txtExclude.setError("Bad format, setting unchanged");
                        return;
                    }
                }

                //if good: save it
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setExcludeText(s.toString());
                }else{
                    DbHelper.saveExcludeText(s.toString(),(DxApplication)requireActivity().getApplication());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        excludeUnknown.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setExcludeUnknown(isChecked);
            }else{
                DbHelper.saveExcludeUnknown(isChecked,(DxApplication)requireActivity().getApplication());
            }
        }));

        strictExclude.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setStrictExclude(isChecked);
            }else{
                DbHelper.saveStrictExclude(isChecked,(DxApplication)requireActivity().getApplication());
            }
        }));

        //proxy related
        enableSocks5Proxy.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setEnableSocks5Proxy(isChecked);
            }else{
                DbHelper.saveEnableSocks5Proxy(isChecked,(DxApplication)requireActivity().getApplication());
            }
            layoutProxy.setVisibility(isChecked?View.VISIBLE:View.GONE);
            if (!isChecked) {
                ((InputMethodManager) requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(requireView().getWindowToken(), 0);
            }
        }));

        txtProxyAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                //accepted format is : ip:port
                String ipPattern = "^((0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)\\.){3}(0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)$";
                if(s.toString().contains(":") && s.toString().split(":").length==2){
                    try{
                        String substring = s.toString().split(":")[1];
                        if(substring.length()<1 || Integer.parseInt(substring)<1 || Integer.parseInt(substring)>65535){
                            txtProxyAddress.setError("Bad format, setting unchanged");
                            return;
                        }

                        if(!s.toString().split(":")[0].matches(ipPattern)){
                            txtProxyAddress.setError("Bad format, setting unchanged");
                            return;
                        }
                    }catch (NumberFormatException ignored){
                        txtProxyAddress.setError("Bad format, setting unchanged");
                        return;
                    }
                }else{
                    if(!s.toString().matches(ipPattern)){
                        txtProxyAddress.setError("Bad format, setting unchanged");
                        return;
                    }
                }
                //if good: save it
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setSocks5AddressAndPort(s.toString());
                }else{
                    DbHelper.saveSocks5AddressAndPort(s.toString(),(DxApplication)requireActivity().getApplication());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        txtProxyUsername.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                //accepted format is : ip:port

                if(s.toString().length()>255){
                    txtProxyUsername.setError("Bad format, setting unchanged");
                    return;
                }

                //if good: save it
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setSocks5Username(s.toString());
                }else{
                    DbHelper.saveSocks5Username(s.toString(),(DxApplication)requireActivity().getApplication());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        txtProxyPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                //accepted format is : ip:port

                if(s.toString().length()>255){
                    txtProxyPassword.setError("Bad format, setting unchanged");
                    return;
                }

                //if good: save it
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setSocks5Password(s.toString());
                }else{
                    DbHelper.saveSocks5Password(s.toString(),(DxApplication)requireActivity().getApplication());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        // bridge related
        bridgesSwitch.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setBridgesEnabled(isChecked);
            }else{
                DbHelper.saveEnableBridges(isChecked,(DxApplication)requireActivity().getApplication());
            }
            bridgesLayout.setVisibility(isChecked?View.VISIBLE:View.GONE);
        }));

        scanBridge.setOnClickListener(v -> {
//            final int QR_RESULT_CODE = 0;
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(requireContext(), SimpleScannerActivity.class);
                intent.putExtra("SCAN_MODE", "ADD_BRIDGE");
                mScanQrCode.launch(intent);
            }else{

                mPermissionResult.launch(Manifest.permission.CAMERA);
//                Utils.getCameraPerms(requireActivity(),2);
            }
        });

        addBridge.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity(), R.style.DXAlertDialog);
            builder.setTitle(R.string.insert_bridge_line);

            // Create a proper input layout
            TextInputLayout inputLayout = new TextInputLayout(requireContext());
            inputLayout.setPadding(32, 0, 32, 0);

            final TextInputEditText input = new TextInputEditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
//            input.setHint(R.string.paste_bridge_lines_here);
            input.setMinHeight(500); // Increased height
            input.setSingleLine(false);
            input.setLines(5);
            input.setMaxLines(8);
            input.setTextSize(16); // Larger text
            input.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
            input.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
            input.setBackgroundResource(R.drawable.edit_text_highlight);

            inputLayout.addView(input);
            builder.setView(inputLayout);

            // Add helpful message with larger text
            builder.setMessage(R.string.bridge_input_help);

            // Set up the buttons with proper styling
            builder.setPositiveButton(R.string.ok, null); // Set to null first to override auto-dismiss
            builder.setNegativeButton(R.string.cancel, null);

            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

                if (positiveButton != null) {
                    positiveButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                    positiveButton.setTextSize(16); // Larger button text
                    positiveButton.setAllCaps(false); // Remove ALL CAPS
                    positiveButton.setPadding(32, 16, 32, 16); // Larger touch area

                    positiveButton.setOnClickListener(view -> {
                        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(requireView().getWindowToken(), 0);

                        // PERFECT MULTILINE PASTE HANDLING
                        String text = input.getText().toString().trim();
                        String[] lines = text.split("\\r?\\n|\\r");
                        List<String> validLines = new ArrayList<>();

                        for (String line : lines) {
                            String trimmedLine = line.trim();
                            if (!trimmedLine.isEmpty()) {
                                if (trimmedLine.toLowerCase().startsWith("bridge ")) {
                                    trimmedLine = trimmedLine.substring(7).trim();
                                }
                                validLines.add(trimmedLine);
                            }
                        }

                        if (isInSetup) {
                            List<String> list = ((CreateUserActivity) requireActivity()).getBridgeList();
                            list.addAll(validLines);
                            ((CreateUserActivity) requireActivity()).setBridgeList(list);
                        } else {
                            for (String line : validLines) {
                                DbHelper.saveBridge(line, (DxApplication) requireActivity().getApplication());
                            }
                        }
                        updateBridgeList();
                        dialog.dismiss();
                    });
                }

                if (negativeButton != null) {
                    negativeButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
                    negativeButton.setTextSize(16); // Larger button text
                    negativeButton.setAllCaps(false); // Remove ALL CAPS
                    negativeButton.setPadding(32, 16, 32, 16); // Larger touch area

                    negativeButton.setOnClickListener(view -> {
                        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(requireView().getWindowToken(), 0);
                        dialog.cancel();
                    });
                }

                // Make message text larger
                TextView messageText = dialog.findViewById(android.R.id.message);
                if (messageText != null) {
                    messageText.setTextSize(14); // Larger message text
                    messageText.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
                    messageText.setLineSpacing(1.2f, 1.2f); // Better line spacing
                }

                // Make title text larger
                TextView titleText = dialog.findViewById(android.R.id.title);
                if (titleText != null) {
                    titleText.setTextSize(18); // Larger title
                    titleText.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                }
            });

            dialog.show();

            // Show keyboard automatically
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });

        //other switches

        allowCalls.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setAcceptingCallsAllowed(isChecked);
            }else{
                DbHelper.saveIsAcceptingCallsAllowed(isChecked,(DxApplication)requireActivity().getApplication());
            }
        }));

        unknownContactsSwitch.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setAcceptingUnknownContactsEnabled(isChecked);
            }else{
                DbHelper.saveIsAcceptingUnknownContactsEnabled(isChecked,(DxApplication)requireActivity().getApplication());
            }
        }));

        allowFilesSwitch.setOnCheckedChangeListener(((buttonView, isChecked) -> {
            if(isInSetup){
                ((CreateUserActivity) requireActivity()).setReceivingFilesAllowed(isChecked);
            }else{
                DbHelper.saveIsReceivingFilesAllowed(isChecked,(DxApplication)requireActivity().getApplication());
            }
            layoutFileLimit.setVisibility(isChecked?View.VISIBLE:View.GONE);
            if (!isChecked) {
                ((InputMethodManager) requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(requireView().getWindowToken(), 0);
            }
        }));

        txtFileLimit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                //accepted format is : 0-999[gb/mb/kb]
                if(!s.toString().trim().endsWith("gb") && !s.toString().trim().endsWith("mb") && !s.toString().trim().endsWith("kb") && !s.toString().trim().endsWith("GB") && !s.toString().trim().endsWith("MB") && !s.toString().trim().endsWith("KB")){
                    txtFileLimit.setError("Bad format, setting unchanged");
                    return;
                }
                try{
                    String substring = s.toString().trim().substring(0, s.toString().length() - 2).trim();
                    if(substring.length()>3 || substring.length()<1 || Integer.parseInt(substring)<1){
                        txtFileLimit.setError("Bad format, setting unchanged");
                        return;
                    }
                }catch (NumberFormatException ignored){
                    txtFileLimit.setError("Bad format, setting unchanged");
                    return;
                }
                //if good: save it
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setFileSizeLimit(s.toString());
                }else{
                    DbHelper.saveFileSizeLimit(s.toString(),(DxApplication)requireActivity().getApplication());
                }

            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        txtCheckAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //validate and output errors
                if(s.toString().contains(":") || s.toString().contains("//")){
                    txtCheckAddress.setError("Bad address, setting unchanged");
                    return;
                }
                if(isInSetup){
                    ((CreateUserActivity) requireActivity()).setCheckAddress(s.toString());
                }else{
                    DbHelper.saveCheckAddress(s.toString(),(DxApplication)requireActivity().getApplication());
                }

            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        List<String> list;
        DxApplication appSettings = (DxApplication) requireActivity().getApplication();
        if(isInSetup){
            list = ((CreateUserActivity) requireActivity()).getBridgeList();

            done.setText(R.string.next_button);
            done.setOnClickListener(v -> ((CreateUserActivity) requireActivity()).changeToPasswordFragment());
        }else{
            // For first-time users (no account yet), DB is null → use empty list
            list = (appSettings.getAccount() != null) ? DbHelper.getBridgeList(appSettings) : new ArrayList<>();

            done.setVisibility(View.GONE);
        }

        LinearLayoutManager layoutManager
                = new LinearLayoutManager(getActivity());
        rvBridges.setLayoutManager(layoutManager);
        BridgeRecyclerViewAdapter adapter = new BridgeRecyclerViewAdapter(this.getActivity(), list);
        rvBridges.setAdapter(adapter);

        // Inflate the layout for this fragment
        return rootView;
    }

    // ==================== BACKUP / RESTORE ====================

    private void forceWhiteText(android.view.View view) {
        if (view instanceof android.widget.TextView) {
            ((android.widget.TextView) view).setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        }
        if (view instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
                forceWhiteText(((android.view.ViewGroup) view).getChildAt(i));
            }
        }
    }

    private ProgressDialog createStyledProgressDialog(Context context, int titleRes, int messageRes) {
        ProgressDialog progress = new ProgressDialog(context, R.style.AppAlertDialog);
        progress.setTitle(titleRes);
        progress.setMessage(context.getString(messageRes) + " 0%");
        progress.setCancelable(false);
        progress.setIndeterminate(false);
        return progress;
    }

    private void showBackupRestoreDialog() {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 24, 32, 16);

        String[] labels = {getString(R.string.backup_option), getString(R.string.restore_option)};
        final AlertDialog[] dialog = new AlertDialog[1];

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            android.widget.Button btn = new android.widget.Button(requireContext());
            btn.setText(labels[idx]);
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
            btn.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.dx_night_700));
            btn.setPadding(32, 16, 32, 16);
            btn.setTextSize(16);
            btn.setOnClickListener(v -> {
                if (idx == 0) pickBackupSaveLocation();
                else pickBackupFile();
                if (dialog[0] != null) dialog[0].dismiss();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.bottomMargin = 12;
            root.addView(btn, p);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.AppAlertDialog);
        builder.setTitle(R.string.backup_or_restore_title);
        builder.setView(root);
        builder.setNegativeButton(R.string.cancel, null);

        dialog[0] = builder.create();
        final AlertDialog d = dialog[0];
        d.setOnShowListener(di -> {
            android.view.View contentView = d.findViewById(android.R.id.content);
            if (contentView != null) forceWhiteText(contentView);
            Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) neg.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        });
        d.show();
    }

    private void showBackupPasswordDialog(android.net.Uri saveUri) {
        LinearLayout rootLayout = new LinearLayout(requireContext());
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(32, 16, 32, 16);

        TextInputLayout inputLayout = new TextInputLayout(requireContext());
        inputLayout.setPadding(0, 0, 0, 16);
        inputLayout.setHint(R.string.backup_password_hint);
        inputLayout.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.dx_white)));

        TextInputEditText passwordInput = new TextInputEditText(requireContext());
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setBackgroundResource(R.drawable.edit_text_highlight);
        passwordInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        passwordInput.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));

        inputLayout.addView(passwordInput);
        rootLayout.addView(inputLayout);

        // Add checkbox for including encrypted files
        android.widget.CheckBox includeFilesCheckbox = new android.widget.CheckBox(requireContext());
        includeFilesCheckbox.setText(R.string.include_encrypted_files);
        includeFilesCheckbox.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        includeFilesCheckbox.setButtonTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_tor)));
        includeFilesCheckbox.setTextSize(14);
        includeFilesCheckbox.setPadding(0, 8, 0, 8);
        rootLayout.addView(includeFilesCheckbox);

        // Add help text
        TextView helpText = new TextView(requireContext());
        helpText.setText(R.string.include_encrypted_files_help);
        helpText.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        helpText.setAlpha(0.7f);
        helpText.setTextSize(12);
        helpText.setPadding(0, 0, 0, 16);
        rootLayout.addView(helpText);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.AppAlertDialog);
        builder.setTitle(R.string.backup_password_title);
        builder.setMessage(R.string.backup_password_message);
        builder.setView(rootLayout);
        builder.setPositiveButton(R.string.action_backup, null);
        builder.setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(di -> {
            android.view.View contentView = dialog.findViewById(android.R.id.content);
            if (contentView != null) forceWhiteText(contentView);
            Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                pos.setOnClickListener(v -> {
                    String password = passwordInput.getText().toString().trim();
                    if (password.length() < 4) {
                        Toast.makeText(getContext(), R.string.backup_password_short, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean includeFiles = includeFilesCheckbox.isChecked();
                    dialog.dismiss();
                    runBackup(saveUri, password, includeFiles);
                });
            }
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
                neg.setOnClickListener(v -> dialog.dismiss());
            }
        });
        dialog.show();
    }

    private void runBackup(android.net.Uri saveUri, String password, boolean includeEncryptedFiles) {
        ProgressDialog progress = createStyledProgressDialog(getContext(), R.string.backup_progress_title, R.string.backup_progress_message);
        progress.show();

        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            File tempBackupFile = null;
            try {
                DxApplication app = (DxApplication) requireActivity().getApplication();

                // Derive encryption key from password
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] encryptionKey = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] rawPassword = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                // Create backup to temp file in cache dir
                File tempDir = app.getCacheDir();
                tempBackupFile = new File(tempDir, "backup_temp.abk");
                if (tempBackupFile.exists()) tempBackupFile.delete();

                final String[] backupErrorMsg = {null};
                String resultPath = BackupHelper.createBackup(app, encryptionKey, rawPassword, tempDir,
                        new BackupHelper.ProgressListener() {
                            @Override
                            public void onProgress(int pct, String msg) {
                                requireActivity().runOnUiThread(() -> {
                                    if (progress.isShowing()) {
                                        progress.setMessage(msg + " " + pct + "%");
                                    }
                                });
                            }
                            @Override
                            public void onComplete(boolean success2, String msg2) {
                                if (!success2 && msg2 != null) backupErrorMsg[0] = msg2;
                            }
                        }, includeEncryptedFiles);

                if (resultPath == null) {
                    requireActivity().runOnUiThread(() -> {
                        try { progress.dismiss(); }
                        finally { requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
                        String msg = backupErrorMsg[0] != null ? backupErrorMsg[0] : "Check log";
                        Toast.makeText(getContext(), getString(R.string.backup_failed, msg),
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Copy encrypted backup to user-chosen location via ContentResolver
                File createdFile = new File(resultPath);
                requireActivity().runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.setMessage(getString(R.string.backup_progress_title) + " 95%");
                    }
                });
                try (java.io.InputStream is = new java.io.FileInputStream(createdFile);
                     java.io.OutputStream os = requireContext().getContentResolver().openOutputStream(saveUri)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                    }
                    os.flush();
                }

                String sizeText = BackupHelper.getBackupInfo(resultPath).getFormattedSize();

                requireActivity().runOnUiThread(() -> {
                    try { progress.dismiss(); }
                    finally { requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }

                    String msg = String.format(getString(R.string.backup_complete_message),
                            sizeText, saveUri.toString());
                    AlertDialog.Builder success = new AlertDialog.Builder(getContext(), R.style.AppAlertDialog);
                    success.setTitle(R.string.backup_complete_title);
                    success.setMessage(msg);
                    success.setPositiveButton(android.R.string.ok, null);
                    AlertDialog sd = success.create();
                    sd.setOnShowListener(di -> {
                        android.view.View cv = sd.findViewById(android.R.id.content);
                        if (cv != null) forceWhiteText(cv);
                        Button pos = sd.getButton(AlertDialog.BUTTON_POSITIVE);
                        if (pos != null) pos.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                    });
                    sd.show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    try { progress.dismiss(); }
                    finally { requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
                    Toast.makeText(getContext(), getString(R.string.backup_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                if (tempBackupFile != null) tempBackupFile.delete();
            }
        }).start();
    }

    private void pickBackupSaveLocation() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        intent.putExtra(Intent.EXTRA_TITLE, "GhostMessenger_Backup_" + timestamp + ".abk");

        mSaveBackup.launch(intent);
    }

    private void pickBackupFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream"});
        mPickBackupFile.launch(intent);
    }

    private void showRestorePasswordDialog(android.net.Uri backupUri) {
        TextInputLayout inputLayout = new TextInputLayout(requireContext());
        inputLayout.setPadding(32, 16, 32, 0);
        inputLayout.setHint(R.string.backup_password_hint);
        inputLayout.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.dx_white)));

        TextInputEditText passwordInput = new TextInputEditText(requireContext());
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setBackgroundResource(R.drawable.edit_text_highlight);
        passwordInput.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
        passwordInput.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));

        inputLayout.addView(passwordInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.AppAlertDialog);
        builder.setTitle(R.string.restore_password_title);
        builder.setMessage(R.string.restore_password_message);
        builder.setView(inputLayout);
        builder.setPositiveButton(R.string.action_restore, null);
        builder.setNegativeButton(R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(di -> {
            android.view.View contentView = dialog.findViewById(android.R.id.content);
            if (contentView != null) forceWhiteText(contentView);
            Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                pos.setOnClickListener(v -> {
                    String password = passwordInput.getText().toString().trim();
                    if (password.length() < 4) {
                        Toast.makeText(getContext(), R.string.backup_password_short, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    runRestore(backupUri, password);
                });
            }
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) {
                neg.setTextColor(ContextCompat.getColor(requireContext(), R.color.dx_white));
                neg.setOnClickListener(v -> dialog.dismiss());
            }
        });
        dialog.show();
    }

    private void runRestore(android.net.Uri backupUri, String password) {
        ProgressDialog progress = createStyledProgressDialog(getContext(), R.string.restore_progress_title, R.string.restore_progress_message);
        progress.show();

        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            File localBackupFile = new File(((DxApplication) requireActivity().getApplication()).getCacheDir(), "restore_input.abk");
            try {
                DxApplication app = (DxApplication) requireActivity().getApplication();

                // Derive encryption key
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] encryptionKey = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                // Copy URI file to a local path we can read
                try (java.io.InputStream is = requireContext().getContentResolver().openInputStream(backupUri);
                     java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(new java.io.FileOutputStream(localBackupFile))) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        bos.write(buf, 0, len);
                    }
                    bos.flush();
                }

                // Run restore with progress
                final String[] errorMsg = {null};
                 boolean success = BackupHelper.restoreBackup(app, localBackupFile.getAbsolutePath(),
                         encryptionKey, password.getBytes(java.nio.charset.StandardCharsets.UTF_8), new BackupHelper.ProgressListener() {
                            @Override
                            public void onProgress(int pct, String msg) {
                                requireActivity().runOnUiThread(() -> {
                                    if (progress.isShowing()) {
                                        progress.setMessage(msg + " " + pct + "%");
                                    }
                                });
                            }
                            @Override
                            public void onComplete(boolean success2, String msg2) {
                                if (!success2 && msg2 != null) errorMsg[0] = msg2;
                            }
                        });

                requireActivity().runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } finally {
                        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }

                    if (success) {
                        AlertDialog.Builder successDialog = new AlertDialog.Builder(getContext(), R.style.AppAlertDialog);
                        successDialog.setTitle(R.string.restore_complete_title);
                        successDialog.setMessage(R.string.restore_complete_message);
                        successDialog.setPositiveButton(android.R.string.ok, (d, w) -> {
                            app.shutdown();
                        });
                        AlertDialog rd = successDialog.create();
                        rd.setOnShowListener(di -> {
                            android.view.View cv = rd.findViewById(android.R.id.content);
                            if (cv != null) forceWhiteText(cv);
                            Button pos = rd.getButton(AlertDialog.BUTTON_POSITIVE);
                            if (pos != null) pos.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_tor));
                        });
                        rd.show();
                    } else {
                        String msg = errorMsg[0] != null ? errorMsg[0] : "Wrong password or corrupted file";
                        Toast.makeText(getContext(), getString(R.string.restore_failed, msg),
                                Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } finally {
                        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                    Toast.makeText(getContext(), getString(R.string.restore_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                localBackupFile.delete();
            }
        }).start();
    }

    public void updateBridgeList(){
        new Thread(()->{
            List<String> list;
            DxApplication appBridge = (DxApplication) requireActivity().getApplication();
            if(isInSetup){
                list = ((CreateUserActivity) requireActivity()).getBridgeList();
            }else{
                list = (appBridge.getAccount() != null) ? DbHelper.getBridgeList(appBridge) : new ArrayList<>();
            }
            requireActivity().runOnUiThread(()->{
                RecyclerView recyclerView = requireActivity().findViewById(R.id.rv_bridges);
                LinearLayoutManager layoutManager
                        = new LinearLayoutManager(getActivity());
                recyclerView.setLayoutManager(layoutManager);
                BridgeRecyclerViewAdapter adapter = new BridgeRecyclerViewAdapter(getActivity(), list);
                recyclerView.setAdapter(adapter);
                adapter.notifyDataSetChanged();
                recyclerView.scrollToPosition(list.size()-1);
            });
        }).start();
    }
}