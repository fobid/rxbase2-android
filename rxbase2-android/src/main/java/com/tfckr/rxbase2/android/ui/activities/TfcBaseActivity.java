package com.tfckr.rxbase2.android.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.AnimRes;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Pair;

import com.tfckr.rxbase2.android.libs.qualifiers.RequiresPresenter;
import com.tfckr.rxbase2.android.libs.utils.BundleUtils;
import com.tfckr.rxbase2.android.libs.utils.PresenterManager;
import com.tfckr.rxbase2.android.ui.data.ActivityResult;
import com.tfckr.rxbase2.android.ui.presenters.TfcBaseActivityPresenter;
import com.tfckr.rxbase2.android.ui.views.TfcBaseActivityView;
import com.trello.rxlifecycle2.RxLifecycle;
import com.trello.rxlifecycle2.android.ActivityEvent;
import com.trello.rxlifecycle2.android.RxLifecycleAndroid;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

public class TfcBaseActivity<Presenter extends TfcBaseActivityPresenter> extends AppCompatActivity implements TfcBaseActivityView {

    private final PublishSubject<Object> back = PublishSubject.create();
    private final BehaviorSubject<ActivityEvent> lifecycle = BehaviorSubject.create();
    private static final String PRESENTER_KEY = "presenter";
    private CompositeDisposable disposables;

    protected Presenter presenter;

    /**
     * Get presenter.
     */
    public Presenter presenter() {
        return presenter;
    }

    /**
     * Returns an observable of the activity's lifecycle events.
     */
    @Override
    public final Observable<ActivityEvent> lifecycle() {
        return lifecycle.hide();
    }

    /**
     * Completes an observable when an {@link ActivityEvent} occurs in the activity's lifecycle.
     */
    public <T> ObservableTransformer<T, T> bindUntilEvent(final ActivityEvent event) {
        return RxLifecycle.bindUntilEvent(lifecycle, event);
    }

    /**
     * Completes an observable when the lifecycle event opposing the current lifecyle event is emitted.
     * For example, if a subscription is made during {@link ActivityEvent#CREATE}, the observable will be completed
     * in {@link ActivityEvent#DESTROY}.
     */
    public <T> ObservableTransformer<T, T> bindToLifecycle() {
        return RxLifecycleAndroid.bindActivity(lifecycle);
    }

    @CallSuper
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        presenter.activityResult(ActivityResult.create(requestCode, resultCode, data));
    }

    @CallSuper
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycle.onNext(ActivityEvent.CREATE);
        assignPresenter(savedInstanceState);
        presenter.intent(getIntent());
        disposables = new CompositeDisposable();
    }

    /**
     * Called when an activity is set to `singleTop` and it is relaunched while at the top of the activity stack.
     */
    @CallSuper
    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        presenter.intent(intent);
    }

    @CallSuper
    @Override
    protected void onStart() {
        super.onStart();
        lifecycle.onNext(ActivityEvent.START);
        presenter.onStart();

        back.compose(bindUntilEvent(ActivityEvent.STOP))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(__ -> goBack(), Throwable::printStackTrace);
    }

    @CallSuper
    @Override
    protected void onResume() {
        super.onResume();
        lifecycle.onNext(ActivityEvent.RESUME);
        presenter.onResume();
    }

    @CallSuper
    @Override
    protected void onPause() {
        lifecycle.onNext(ActivityEvent.PAUSE);
        presenter.onPause();
        super.onPause();
    }

    @CallSuper
    @Override
    protected void onStop() {
        lifecycle.onNext(ActivityEvent.STOP);
        presenter.onStop();
        super.onStop();
    }

    @CallSuper
    @Override
    protected void onDestroy() {
        presenter.onDestroy();
        lifecycle.onNext(ActivityEvent.DESTROY);
        disposables.dispose();
        super.onDestroy();

        if (isFinishing()) {
            if (presenter != null) {
                PresenterManager.getInstance().destroy(presenter);
                presenter = null;
            }
        }
    }

    /**
     * @deprecated Use {@link #back()} instead.
     * <p>
     * In rare situations, onBack can be triggered after {@link #onSaveInstanceState(Bundle)} has been called.
     * This causes an {@link IllegalStateException} in the fragment manager's `checkStateLoss` method, because the
     * UI stateCircle has changed after being saved. The sequence of events might look like this:
     * <p>
     * onSaveInstanceState -> onStop -> onBack
     * <p>
     * To avoid that situation, we need to ignore calls to `onBack` after the activity has been saved. Since
     * the activity is stopped after `onSaveInstanceState` is called, we can create an observable of back events,
     * and a subscription that calls super.onBack() only when the activity has not been stopped.
     */
    @CallSuper
    @Override
    @Deprecated
    public void onBackPressed() {
        back();
    }

    /**
     * Call when the user wants triggers a back event, e.g. clicking back in a toolbar or pressing the device back button.
     */
    public void back() {
        back.onNext(0);
    }

    @CallSuper
    @Override
    protected void onSaveInstanceState(final @NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        final Bundle viewModelEnvelope = new Bundle();
        if (presenter != null) {
            PresenterManager.getInstance().save(presenter, viewModelEnvelope);
        }

        outState.putBundle(PRESENTER_KEY, viewModelEnvelope);
    }

    /**
     * Override in subclasses for custom exit transitions. First item in pair is the enter animation,
     * second item in pair is the exit animation.
     */
    protected
    @Nullable
    Pair<Integer, Integer> exitTransition() {
        return null;
    }

    protected final void startActivityWithTransition(final @NonNull Intent intent, final @AnimRes int enterAnim,
                                                     final @AnimRes int exitAnim) {
        startActivity(intent);
        overridePendingTransition(enterAnim, exitAnim);
    }

    /**
     * Triggers a back press with an optional transition.
     */
    private void goBack() {
        super.onBackPressed();

        final Pair<Integer, Integer> exitTransitions = exitTransition();
        if (exitTransitions != null) {
            overridePendingTransition(exitTransitions.first, exitTransitions.second);
        }
    }

    private void assignPresenter(final @Nullable Bundle presenterEnvelope) {
        if (presenter == null) {
            final RequiresPresenter annotation = getClass().getAnnotation(RequiresPresenter.class);
            final Class<Presenter> presenterClass = annotation == null ? null : (Class<Presenter>) annotation.value();
            if (presenterClass != null) {
                presenter = PresenterManager.getInstance().fetch(this,
                        presenterClass,
                        BundleUtils.maybeGetBundle(presenterEnvelope, PRESENTER_KEY));
            }
        }
    }
}
