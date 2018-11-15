package io.flutter.plugins.googlemaps;

import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.CREATED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.DESTROYED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.PAUSED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.RESUMED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.STARTED;
import static io.flutter.plugins.googlemaps.GoogleMapsPlugin.STOPPED;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;

import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONObject;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.platform.PlatformView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Controller of a single GoogleMaps MapView instance. */
final class GoogleMapController
        implements Application.ActivityLifecycleCallbacks,
        GoogleMap.OnCameraIdleListener,
        GoogleMap.OnCameraMoveListener,
        GoogleMap.OnCameraMoveStartedListener,
        GoogleMap.OnInfoWindowClickListener,
        GoogleMap.OnMarkerClickListener,
        GoogleMapOptionsSink,
        MethodChannel.MethodCallHandler,
        OnMapReadyCallback,
        OnMarkerTappedListener,
        PlatformView {
  private final int id;
  private final AtomicInteger activityState;
  private final MethodChannel methodChannel;
  private final PluginRegistry.Registrar registrar;
  private final MapView mapView;
  private final Map<String, MarkerController> markers;
  private final List<Marker> markerList;
  private GoogleMap googleMap;
  private boolean trackCameraPosition = false;
  private boolean disposed = false;
  private final float density;
  private MethodChannel.Result mapReadyResult;
  private final int registrarActivityHashCode;

  GoogleMapController(
          int id,
          Context context,
          AtomicInteger activityState,
          PluginRegistry.Registrar registrar,
          GoogleMapOptions options) {
    this.id = id;
    this.activityState = activityState;
    this.registrar = registrar;
    this.mapView = new MapView(context, options);
    this.markers = new HashMap<>();
    this.density = context.getResources().getDisplayMetrics().density;
    methodChannel =
            new MethodChannel(registrar.messenger(), "plugins.flutter.io/google_maps_" + id);
    methodChannel.setMethodCallHandler(this);
    this.registrarActivityHashCode = registrar.activity().hashCode();
    markerList = new ArrayList<>();
  }

  @Override
  public View getView() {
    return mapView;
  }

  void init() {
    switch (activityState.get()) {
      case STOPPED:
        mapView.onCreate(null);
        mapView.onStart();
        mapView.onResume();
        mapView.onPause();
        mapView.onStop();
        break;
      case PAUSED:
        mapView.onCreate(null);
        mapView.onStart();
        mapView.onResume();
        mapView.onPause();
        break;
      case RESUMED:
        mapView.onCreate(null);
        mapView.onStart();
        mapView.onResume();
        break;
      case STARTED:
        mapView.onCreate(null);
        mapView.onStart();
        break;
      case CREATED:
        mapView.onCreate(null);
        break;
      case DESTROYED:
        // Nothing to do, the activity has been completely destroyed.
        break;
      default:
        throw new IllegalArgumentException(
                "Cannot interpret " + activityState.get() + " as an activity state");
    }
    registrar.activity().getApplication().registerActivityLifecycleCallbacks(this);
    mapView.getMapAsync(this);
  }

  private void moveCamera(CameraUpdate cameraUpdate) {
    googleMap.moveCamera(cameraUpdate);
  }

  private void animateCamera(CameraUpdate cameraUpdate) {
    googleMap.animateCamera(cameraUpdate);
  }

  private CameraPosition getCameraPosition() {
    return trackCameraPosition ? googleMap.getCameraPosition() : null;
  }

  private MarkerBuilder newMarkerBuilder() {
    return new MarkerBuilder(this);
  }

  Marker addMarker(MarkerOptions markerOptions, boolean consumesTapEvents) {
    final Marker marker = googleMap.addMarker(markerOptions);
    markers.put(marker.getId(), new MarkerController(marker, consumesTapEvents, this));
    markerList.add(marker);
//    drawRoute();
    return marker;
  }

  private void drawRoute() {
    if (markerList.size() < 2) return;
    DownloadTask downloadTask = new DownloadTask();
    // Start downloading json data from Google Directions API
    downloadTask.execute(getDirectionsUrl());
  }

  private String getDirectionsUrl() {
    int markerLength = markerList.size();
    if (markerLength < 2) return "";

    // Origin of route
    String str_origin = "origin=" + markerList.get(0).getPosition().latitude + "," + markerList.get(0).getPosition().longitude;

    // Destination of route
    String str_dest = "destination=" + markerList.get(markerLength - 1).getPosition().latitude + "," + markerList.get(markerLength - 1).getPosition().longitude;

    String waypoints = "";
    if (markerLength >= 3) {
      for (int i = 1; i < markerLength - 2; i++) {
        if (i > 1) waypoints += "|";
        waypoints += markerList.get(i).getPosition().latitude + "," + markerList.get(i).getPosition().latitude;
      }
    }
    String str_waypoints = markerLength >= 3 ? "waypoints=" + waypoints : "";

    // Sensor enabled
    String sensor = "sensor=false";
    String mode = "mode=driving";

    // Building the parameters to the web service
    String parameters = str_origin + "&" + str_dest + "&" + str_waypoints + "&" + sensor + "&" + mode;

    // Output format
    String output = "json";

    // Building the url to the web service
    String url = "https://maps.googleapis.com/maps/api/directions/" + output + "?" + parameters + "&key=AIzaSyBoG7XAHKf5cuMHGjMjMOCepGOhsFRsxPo";


    return url;
  }

  private String downloadUrl(String strUrl) throws IOException {
    String data = "";
    InputStream iStream = null;
    HttpURLConnection urlConnection = null;
    try {
      URL url = new URL(strUrl);

      urlConnection = (HttpURLConnection) url.openConnection();

      urlConnection.connect();

      iStream = urlConnection.getInputStream();

      BufferedReader br = new BufferedReader(new InputStreamReader(iStream));

      StringBuffer sb = new StringBuffer();

      String line = "";
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }

      data = sb.toString();

      br.close();

    } catch (Exception e) {
      Log.d("Exception", e.toString());
    } finally {
      iStream.close();
      urlConnection.disconnect();
    }
    return data;
  }

  private class DownloadTask extends AsyncTask<String, Void, String> {

    @Override
    protected String doInBackground(String... url) {

      String data = "";

      try {
        data = downloadUrl(url[0]);
      } catch (Exception e) {
        Log.d("Background Task", e.toString());
      }
      return data;
    }

    @Override
    protected void onPostExecute(String result) {
      super.onPostExecute(result);

      ParserTask parserTask = new ParserTask();


      parserTask.execute(result);

    }
  }


  /**
   * A class to parse the Google Places in JSON format
   */
  private class ParserTask extends AsyncTask<String, Integer, List<List<HashMap<String, String>>>> {

    // Parsing the data in non-ui thread
    @Override
    protected List<List<HashMap<String, String>>> doInBackground(String... jsonData) {

      JSONObject jObject;
      List<List<HashMap<String, String>>> routes = null;

      try {
        jObject = new JSONObject(jsonData[0]);
        DirectionsJSONParser parser = new DirectionsJSONParser();

        routes = parser.parse(jObject);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return routes;
    }

    @Override
    protected void onPostExecute(List<List<HashMap<String, String>>> result) {
      ArrayList points = null;
      PolylineOptions lineOptions = null;
      MarkerOptions markerOptions = new MarkerOptions();

      for (int i = 0; i < result.size(); i++) {
        points = new ArrayList();
        lineOptions = new PolylineOptions();

        List<HashMap<String, String>> path = result.get(i);

        for (int j = 0; j < path.size(); j++) {
          HashMap<String, String> point = path.get(j);

          double lat = Double.parseDouble(point.get("lat"));
          double lng = Double.parseDouble(point.get("lng"));
          LatLng position = new LatLng(lat, lng);

          points.add(position);
        }

        lineOptions.addAll(points);
        lineOptions.width(12);
        lineOptions.color(Color.RED);
        lineOptions.geodesic(true);

      }

// Drawing polyline in the Google Map for the i-th route
      googleMap.addPolyline(lineOptions);
    }
  }

  private void removeMarker(String markerId) {
    final MarkerController markerController = markers.remove(markerId);
    if (markerController != null) {
      markerController.remove();
    }
  }

  private MarkerController marker(String markerId) {
    final MarkerController marker = markers.get(markerId);
    if (marker == null) {
      throw new IllegalArgumentException("Unknown marker: " + markerId);
    }
    return marker;
  }

  @Override
  public void onMapReady(GoogleMap googleMap) {
    this.googleMap = googleMap;
    googleMap.setOnInfoWindowClickListener(this);
    if (mapReadyResult != null) {
      mapReadyResult.success(null);
      mapReadyResult = null;
    }
    googleMap.setOnCameraMoveStartedListener(this);
    googleMap.setOnCameraMoveListener(this);
    googleMap.setOnCameraIdleListener(this);
    googleMap.setOnMarkerClickListener(this);
  }

  @Override
  public void onMethodCall(MethodCall call, MethodChannel.Result result) {
    switch (call.method) {
      case "map#waitForMap":
        if (googleMap != null) {
          result.success(null);
          return;
        }
        mapReadyResult = result;
        break;
      case "map#update":
      {
        Convert.interpretGoogleMapOptions(call.argument("options"), this);
        result.success(Convert.toJson(getCameraPosition()));
        break;
      }
      case "camera#move":
      {
        final CameraUpdate cameraUpdate =
                Convert.toCameraUpdate(call.argument("cameraUpdate"), density);
        moveCamera(cameraUpdate);
        result.success(null);
        break;
      }
      case "camera#animate":
      {
        final CameraUpdate cameraUpdate =
                Convert.toCameraUpdate(call.argument("cameraUpdate"), density);
        animateCamera(cameraUpdate);
        result.success(null);
        break;
      }
      case "marker#add":
      {
        final MarkerBuilder markerBuilder = newMarkerBuilder();
        Convert.interpretMarkerOptions(call.argument("options"), markerBuilder);
        final String markerId = markerBuilder.build();
        result.success(markerId);
        break;
      }
      case "marker#remove":
      {
        final String markerId = call.argument("marker");
        removeMarker(markerId);
        result.success(null);
        break;
      }
      case "marker#update":
      {
        final String markerId = call.argument("marker");
        final MarkerController marker = marker(markerId);
        Convert.interpretMarkerOptions(call.argument("options"), marker);
        result.success(null);
        break;
      }
      default:
        result.notImplemented();
    }
  }

  @Override
  public void onCameraMoveStarted(int reason) {
    final Map<String, Object> arguments = new HashMap<>(2);
    boolean isGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE;
    arguments.put("isGesture", isGesture);
    methodChannel.invokeMethod("camera#onMoveStarted", arguments);
  }

  @Override
  public void onInfoWindowClick(Marker marker) {
    final Map<String, Object> arguments = new HashMap<>(2);
    arguments.put("marker", marker.getId());
    methodChannel.invokeMethod("infoWindow#onTap", arguments);
  }

  @Override
  public void onCameraMove() {
    if (!trackCameraPosition) {
      return;
    }
    final Map<String, Object> arguments = new HashMap<>(2);
    arguments.put("position", Convert.toJson(googleMap.getCameraPosition()));
    methodChannel.invokeMethod("camera#onMove", arguments);
  }

  @Override
  public void onCameraIdle() {
    methodChannel.invokeMethod("camera#onIdle", Collections.singletonMap("map", id));
  }

  @Override
  public void onMarkerTapped(Marker marker) {
    final Map<String, Object> arguments = new HashMap<>(2);
    arguments.put("marker", marker.getId());
    methodChannel.invokeMethod("marker#onTap", arguments);
  }

  @Override
  public boolean onMarkerClick(Marker marker) {
    final MarkerController markerController = markers.get(marker.getId());
    return (markerController != null && markerController.onTap());
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    mapView.onDestroy();
    registrar.activity().getApplication().unregisterActivityLifecycleCallbacks(this);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onCreate(savedInstanceState);
  }

  @Override
  public void onActivityStarted(Activity activity) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onStart();
  }

  @Override
  public void onActivityResumed(Activity activity) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onResume();
  }

  @Override
  public void onActivityPaused(Activity activity) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onPause();
  }

  @Override
  public void onActivityStopped(Activity activity) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onStop();
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onSaveInstanceState(outState);
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    if (disposed || activity.hashCode() != registrarActivityHashCode) {
      return;
    }
    mapView.onDestroy();
  }

  // GoogleMapOptionsSink methods

  @Override
  public void setCameraPosition(CameraPosition position) {
    googleMap.moveCamera(CameraUpdateFactory.newCameraPosition(position));
  }

  @Override
  public void setCameraTargetBounds(LatLngBounds bounds) {
    googleMap.setLatLngBoundsForCameraTarget(bounds);
  }

  @Override
  public void setCompassEnabled(boolean compassEnabled) {
    googleMap.getUiSettings().setCompassEnabled(compassEnabled);
  }

  @Override
  public void setMapType(int mapType) {
    googleMap.setMapType(mapType);
  }

  @Override
  public void setTrackCameraPosition(boolean trackCameraPosition) {
    this.trackCameraPosition = trackCameraPosition;
  }

  @Override
  public void setRotateGesturesEnabled(boolean rotateGesturesEnabled) {
    googleMap.getUiSettings().setRotateGesturesEnabled(rotateGesturesEnabled);
  }

  @Override
  public void setScrollGesturesEnabled(boolean scrollGesturesEnabled) {
    googleMap.getUiSettings().setScrollGesturesEnabled(scrollGesturesEnabled);
  }

  @Override
  public void setTiltGesturesEnabled(boolean tiltGesturesEnabled) {
    googleMap.getUiSettings().setTiltGesturesEnabled(tiltGesturesEnabled);
  }

  @Override
  public void setMinMaxZoomPreference(Float min, Float max) {
    googleMap.resetMinMaxZoomPreference();
    if (min != null) {
      googleMap.setMinZoomPreference(min);
    }
    if (max != null) {
      googleMap.setMaxZoomPreference(max);
    }
  }

  @Override
  public void setZoomGesturesEnabled(boolean zoomGesturesEnabled) {
    googleMap.getUiSettings().setZoomGesturesEnabled(zoomGesturesEnabled);
  }
}