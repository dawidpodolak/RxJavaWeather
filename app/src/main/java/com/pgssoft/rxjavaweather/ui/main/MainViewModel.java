package com.pgssoft.rxjavaweather.ui.main;

import android.databinding.ObservableBoolean;

import com.jakewharton.rxrelay2.PublishRelay;
import com.pgssoft.rxjavaweather.DBManager;
import com.pgssoft.rxjavaweather.api.Api;
import com.pgssoft.rxjavaweather.api.WeatherService;
import com.pgssoft.rxjavaweather.model.city.City;
import com.pgssoft.rxjavaweather.model.condition.Condition;
import com.pgssoft.rxjavaweather.model.condition.ConditionResponse;
import com.pgssoft.rxjavaweather.ui.OpenActivityEvent;

import org.reactivestreams.Publisher;

import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;
import io.reactivex.SingleTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

/**
 * Created by dpodolak on 13.04.2017.
 */
public class MainViewModel {

    /**
     * Flag which hide or show placeholder and recyclerView
     */
    public ObservableBoolean placeholderVisible = new ObservableBoolean(true);

    /**
     * Emit lists with cities to show
     */
    private PublishRelay<List<City>> citiesPublish = PublishRelay.create();

    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    /**
     * Emit events in order open proper activity
     */
    private PublishRelay<OpenActivityEvent> openActivityRelay = PublishRelay.create();

    private WeatherService weatherService;

    private DBManager dbManager;


    /**
     * Convert ConditionResponse into condition
     */
    SingleTransformer<ConditionResponse, Condition> networkTransformer = new SingleTransformer<ConditionResponse, Condition>() {
        @Override
        public SingleSource<Condition> apply(@NonNull Single<ConditionResponse> upstream) {
            return upstream.map(ConditionResponse::getCondition);
        }
    };


    public MainViewModel(Api api, DBManager dbManager) {

        this.dbManager = dbManager;
        weatherService = api.getWeatherService();
        updateAndShowWeather();


    }

    /**
     * Get cities from db -> update wheater for thos cities and pass it to view
     */
    public void updateAndShowWeather() {

        dbManager.getCityHelper().getCities() //read cities from db
                .flatMapSingle(someCity ->

                        //get conditions for specific city, put it into the db and update city object
                        weatherService.getWeather(someCity.getFullPath())
                                .compose(networkTransformer) // it could be replace by map, but it is made for test purpose
                                .map(condition -> {
                                    someCity.setCondition(condition);
                                    return someCity;
                                })
                                .doOnError(t -> Timber.e(t.getMessage()))
                                .retryWhen(new Function<Flowable<Throwable>, Publisher<?>>() {
                                    @Override
                                    public Publisher<?> apply(Flowable<Throwable> throwableFlowable) throws Exception {
                                        Flowable<Long> delayFactors = Flowable.range(0, 5).map(f -> (long) f);
                                        return throwableFlowable
                                                .zipWith(delayFactors, (t, df) -> df)
                                                .map(df -> (long) Math.pow(2, df))
                                                .doOnNext(df -> Timber.e("Factor: " + df))
                                                .doOnComplete(() -> Timber.d("Retry when complete"))
                                                .flatMap(df -> Flowable.timer(df, TimeUnit.SECONDS));
                                    }
                                })
                                .onErrorResumeNext(e -> Single.just(someCity))) // if error
                .toList() //catch all cities (with actual wheather) in to a list
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<List<City>>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onSuccess(@NonNull List<City> cities) {

                        placeholderVisible.set(cities.isEmpty());
                        citiesPublish.accept(cities);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        e.printStackTrace();
                    }
                });
    }

    /**
     * open activity to add cities
     */
    public void addActivity() {
        openActivityRelay.accept(new OpenActivityEvent(OpenActivityEvent.AddActivity));
    }

    public void close() {
        compositeDisposable.clear();
    }

    public Observable<OpenActivityEvent> getOpenActivityObservable() {
        return openActivityRelay;
    }

    public Observable<List<City>> citiesObservable() {
        return citiesPublish;
    }
}
