package ai.elimu.crowdsource.ui.authentication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;
import org.walletconnect.Session;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.elimu.crowdsource.BaseApplication;
import ai.elimu.crowdsource.BuildConfig;
import ai.elimu.crowdsource.MainActivity;
import ai.elimu.crowdsource.R;
import ai.elimu.crowdsource.rest.ContributorService;
import ai.elimu.crowdsource.util.EthersUtils;
import ai.elimu.crowdsource.util.SharedPreferencesHelper;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import timber.log.Timber;

/**
 * Prompts the Contributor for access to her Web3 account. Then stores the account details in the
 * webapp's database.
 * <p>
 * See https://docs.metamask.io/guide/signing-data.html#signing-data-with-metamask
 */
public class SignInWithWeb3Activity extends AppCompatActivity implements Session.Callback {

    private static String w3Account = "";
    public static final String W3_SIGN_MESSAGE = "elimu.ai";

    private Button connectW3Button;

    private ProgressBar signInProgressBar;


    private long txRequest;
    private Button signInW3Button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Timber.i("onCreate");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_sign_in_with_web3);

        signInProgressBar = findViewById(R.id.sign_in_progressbar);

        signInW3Button = findViewById(R.id.sign_in_web3_button);
        connectW3Button = findViewById(R.id.connect_web3_button);
        connectW3Button.setOnClickListener(view -> {
            try {
                BaseApplication.resetSession();
            } catch (Exception e) {
                Toast.makeText(this, "WalletConnect session is null!", Toast.LENGTH_LONG).show();
                return;
            }
            BaseApplication.session.addCallback(SignInWithWeb3Activity.this);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(BaseApplication.config.toWCUri()));
            startActivity(i);
        });

        signInW3Button.setOnClickListener(view -> {
            if (BaseApplication.session.approvedAccounts() == null || BaseApplication.session.approvedAccounts().size() == 0) {
                Toast.makeText(this, "No approved account found", Toast.LENGTH_LONG).show();
                return;
            }
            String from = BaseApplication.session.approvedAccounts().get(0);
            long txRequest = System.currentTimeMillis();
            Timber.tag("web3.wallet").i("from: %s", from);
            BaseApplication.session.performMethodCall(
                    new Session.MethodCall.SignMessage(
                            txRequest,
                            from,
                            W3_SIGN_MESSAGE
                    ),
                    response -> {
                        if (response.id() == SignInWithWeb3Activity.this.txRequest) {
                            SignInWithWeb3Activity.this.txRequest = -1;
                            if (response.getResult() != null) {

                                Timber.tag("web3.wallet").i("signed message: %s", response);
                                boolean recovered = false;
                                recovered = EthersUtils.verifyMessage(from, W3_SIGN_MESSAGE, response.getResult().toString());
                                Timber.tag("web3.wallet").i("recovered address: %s", recovered);
                                if (recovered) {
                                    w3Account = from;
                                }
                            }
                        }
                        return null;
                    }
            );
            navigateToWallet();
            this.txRequest = txRequest;
        });

    }

    @Override
    protected void onStart() {
        Timber.i("onStart");
        super.onStart();

        // Web3 Sign In button.
        initialSetup();

        if (!w3Account.equals("")){
            updateUIW3(w3Account);
        }


    }

    private void updateUIW3(String web3Account) {
        Timber.i("updateUI");


        // Get the details from the Contributor's Google account
        String providerIdGoogle = "";
        String email = "";
        String firstName = web3Account;
        String lastName = "";
        String imageUrl = "";

        // Display the progressbar while connecting to the webapp
        signInW3Button.setVisibility(View.GONE);
        connectW3Button.setVisibility(View.GONE);
        signInProgressBar.setVisibility(View.VISIBLE);

        // Prepare JSON object to be sent to the webapp's REST API
        JSONObject contributorJSONObject = new JSONObject();
        try {
            contributorJSONObject.put("providerIdWeb3", web3Account);
            contributorJSONObject.put("providerIdGoogle", web3Account);
            contributorJSONObject.put("email", web3Account);
        } catch (JSONException e) {
            Timber.e(e);
        }
        Timber.i("contributorJSONObject: " + contributorJSONObject);

        // Register the Contributor in the webapp's database
        BaseApplication baseApplication = (BaseApplication) getApplication();
        Retrofit retrofit = baseApplication.getRetrofit();
        ContributorService contributorService = retrofit.create(ContributorService.class);
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), contributorJSONObject.toString());
        Call<ResponseBody> call = contributorService.createContributor(requestBody);
        Timber.i("call.request(): " + call.request());
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                Timber.i("run");

                try {
                    Response<ResponseBody> response = call.execute();
                    Timber.i("response: " + response);
                    Timber.i("response.isSuccessful(): " + response.isSuccessful());
                    if (response.isSuccessful()) {
                        String bodyString = response.body().string();
                        Timber.i("bodyString: " + bodyString);

                        // Persist the Contributor's account details in SharedPreferences
                        SharedPreferencesHelper.storeWeb3Account(getApplicationContext(), web3Account);

                        // Redirect to the MainActivity
                        Intent mainActivityIntent = new Intent(getApplicationContext(), MainActivity.class);
                        startActivity(mainActivityIntent);
                        finish();
                    } else {
                        String errorBodyString = response.errorBody().string();
                        Timber.e("errorBodyString: " + errorBodyString);
                        // TODO: Handle error

                        runOnUiThread(() -> {
                            Toast.makeText(getApplicationContext(), "Error " + response.code() + ": \"" + response.message() + "\"", Toast.LENGTH_LONG).show();

                            // Hide the progressbar
                            signInW3Button.setVisibility(View.VISIBLE);
                            connectW3Button.setVisibility(View.VISIBLE);
                            signInProgressBar.setVisibility(View.GONE);
                        });
                    }
                } catch (IOException e) {
                    Timber.e(e, null);
                    // TODO: Handle error

                    runOnUiThread(() -> {
                        Toast.makeText(getApplicationContext(), "Error: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();

                        // Hide the progressbar
                        signInW3Button.setVisibility(View.VISIBLE);
                        connectW3Button.setVisibility(View.VISIBLE);
                        signInProgressBar.setVisibility(View.GONE);
                    });
                }
            }
        });

    }


    @Override
    public void onMethodCall(@NonNull Session.MethodCall methodCall) {

    }

    private void sessionApproved() {
        signInW3Button.setVisibility(View.VISIBLE);
    }

    private void sessionClosed() {
        signInW3Button.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onStatus(@NonNull Session.Status status) {
        if (status instanceof Session.Status.Error) {

        } else if (status instanceof Session.Status.Approved) {
            sessionApproved();
        } else if (status instanceof Session.Status.Closed) {
            sessionClosed();
        } else if (status instanceof Session.Status.Connected) {
            requestConnectionToWallet();
        } else if (status instanceof Session.Status.Disconnected) {

        }
    }

    private void requestConnectionToWallet() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(BaseApplication.config.toWCUri()));
        startActivity(i);
    }

    private void navigateToWallet() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse("wc:"));
        startActivity(i);
    }

    private void initialSetup() {
        if (BaseApplication.session != null) {
            BaseApplication.session.addCallback(this);
            sessionApproved();
        }

    }

    @Override
    protected void onDestroy() {
        BaseApplication.session.removeCallback(this);
        super.onDestroy();
    }

}