package com.eaglesakura.android.firebase.auth;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.auth.GoogleAuthProvider;

import com.eaglesakura.android.error.NetworkNotConnectException;
import com.eaglesakura.android.firebase.error.FirebaseAuthFailedException;
import com.eaglesakura.android.gms.util.PlayServiceUtil;
import com.eaglesakura.lambda.CallbackUtils;
import com.eaglesakura.lambda.CancelCallback;
import com.eaglesakura.thread.Holder;
import com.eaglesakura.util.StringUtil;
import com.eaglesakura.util.Util;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Firebaseの認証用Util
 *
 * 認証待ち等のawait系を補助する。
 */
public class FirebaseAuthorizeManager {
    private final FirebaseAuth mAuth = FirebaseAuth.getInstance();

    private final Object lock = new Object();

    /**
     * ロード済みトークン
     */
    private String mToken;

    /**
     * トークンの有効期限
     */
    private long mTokenExpireTime;

    private FirebaseAuthorizeManager() {

    }

    @Nullable
    public FirebaseUser getCurrentUser() {
        return mAuth.getCurrentUser();
    }

    @NonNull
    public String getToken(CancelCallback cancelCallback) throws FirebaseAuthFailedException, InterruptedException {
        if (!StringUtil.isEmpty(mToken) && System.currentTimeMillis() < mTokenExpireTime) {
            // トークンがまだ有効である
            return mToken;
        }

        // トークンをリフレッシュする

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            throw new FirebaseAuthFailedException("not authorized");
        }

        Task<GetTokenResult> task = PlayServiceUtil.await(user.getIdToken(true), cancelCallback);
        if (!task.isSuccessful()) {
            throw new FirebaseAuthFailedException("getIdToken(true) failed");
        }

        // 55分で期限切れする
        mToken = task.getResult().getToken();
        mTokenExpireTime = System.currentTimeMillis() + (TimeUnit.MINUTES.toMillis(55));
        return mToken;
    }

    /**
     * ログイン完了を待つ
     */
    @NonNull
    public FirebaseUser await(CancelCallback cancelCallback) throws InterruptedException {
        FirebaseUser item;
        while ((item = getCurrentUser()) == null) {
            if (CallbackUtils.isCanceled(cancelCallback)) {
                throw new InterruptedException();
            }
            Util.sleep(1);
        }

        return item;
    }

    /**
     * Google Accountで認証を行う
     *
     * @param account        ログインされたアカウント情報
     * @param cancelCallback コールバック
     */
    public FirebaseAuthorizeManager signIn(@NonNull GoogleSignInAccount account, @NonNull CancelCallback cancelCallback) throws InterruptedException, FirebaseAuthFailedException, NetworkNotConnectException {
        return signIn(
                GoogleAuthProvider.getCredential(account.getIdToken(), null),
                cancelCallback
        );
    }

    /**
     * サインインを行い、結果を得る
     *
     * signIn後はgetCurrentUserが行える。
     */
    public FirebaseAuthorizeManager signIn(@NonNull AuthCredential credential, @NonNull CancelCallback cancelCallback) throws InterruptedException, FirebaseAuthFailedException, NetworkNotConnectException {
        synchronized (lock) {
            Context context = FirebaseApp.getInstance().getApplicationContext();

            Task<AuthResult> task = PlayServiceUtil.awaitWithNetwork(context, mAuth.signInWithCredential(credential), cancelCallback);
            if (!task.isSuccessful()) {
                throw new FirebaseAuthFailedException();
            }
            return this;
        }
    }

    /**
     * 匿名ログインを行う
     */
    public FirebaseAuthorizeManager signInAnonymously(@NonNull CancelCallback cancelCallback) throws InterruptedException, FirebaseAuthFailedException, NetworkNotConnectException {
        synchronized (lock) {
            Context context = FirebaseApp.getInstance().getApplicationContext();

            Task<AuthResult> task = PlayServiceUtil.awaitWithNetwork(context, mAuth.signInAnonymously(), cancelCallback);
            if (!task.isSuccessful()) {
                throw new FirebaseAuthFailedException();
            }
            return this;
        }
    }

    /**
     * 匿名ログインを行う
     */
    public FirebaseAuthorizeManager signInWithCustomToken(@NonNull String customAuthToken, @NonNull CancelCallback cancelCallback) throws InterruptedException, FirebaseAuthFailedException, NetworkNotConnectException {
        synchronized (lock) {
            Context context = FirebaseApp.getInstance().getApplicationContext();

            Task<AuthResult> task = PlayServiceUtil.awaitWithNetwork(context, mAuth.signInWithCustomToken(customAuthToken), cancelCallback);
            if (!task.isSuccessful()) {
                throw new FirebaseAuthFailedException();
            }
            return this;
        }
    }

    /**
     * サインアウトを完了させる
     */
    public FirebaseAuthorizeManager signOut(CancelCallback cancelCallback) throws InterruptedException, NetworkNotConnectException {
        synchronized (lock) {
            if (getCurrentUser() == null) {
                return this;
            }

            Holder<Boolean> holder = new Holder<>();
            holder.set(Boolean.FALSE);
            FirebaseAuth.AuthStateListener listener = newAuth -> {
                holder.set(Boolean.TRUE);
            };
            try {
                mAuth.addAuthStateListener(listener);
                mAuth.signOut();

                while (!holder.get()) {
                    if (CallbackUtils.isCanceled(cancelCallback)) {
                        throw new InterruptedException();
                    }
                    Util.sleep(1);
                }

                return this;
            } finally {
                mAuth.removeAuthStateListener(listener);
            }
        }
    }


    private static FirebaseAuthorizeManager sInstance;

    public synchronized static FirebaseAuthorizeManager getInstance() {
        if (sInstance == null) {
            sInstance = new FirebaseAuthorizeManager();
        }
        return sInstance;
    }
}
