package ai.e_motion.mynewmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.directions.route.*
import com.google.android.gms.common.api.Status
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsResponse
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.plugins.annotation.Symbol
import com.mapbox.mapboxsdk.plugins.annotation.SymbolManager
import com.mapbox.mapboxsdk.plugins.annotation.SymbolOptions
import com.mapbox.mapboxsdk.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncher
import com.mapbox.services.android.navigation.ui.v5.NavigationLauncherOptions
import com.mapbox.services.android.navigation.ui.v5.route.NavigationMapRoute
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity(), OnMapReadyCallback,
    PermissionsListener, MapboxMap.OnMapClickListener {

    var map: MapboxMap? = null
    private var permissionsManager: PermissionsManager = PermissionsManager(this)
    private var originLocation: Location? = null
    var originPosition: Point? = null
    var destinationPosition: Point? = null
    private var navigationMapRoute: NavigationMapRoute? = null
    var start: com.google.android.gms.maps.model.LatLng? = null
    var end: com.google.android.gms.maps.model.LatLng? = null
    var routingListener: RoutingListener? = null
    var coordinates = ArrayList<Point>()
    var routeOptions: RouteOptions? = null
    private var directionRouteByGoogle: DirectionsRoute? = null
    private var directionRouteByMapBox: DirectionsRoute? = null
    private var symbolManager: SymbolManager? = null
    private var symbol: Symbol? = null
    private var locationComponent: LocationComponent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.access_token))
        setContentView(R.layout.activity_main)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        autoComplete()

        btnSearch.setOnClickListener {
            if (directionRouteByMapBox != null) {
                val options: NavigationLauncherOptions = NavigationLauncherOptions.builder()
                    .directionsRoute(directionRouteByMapBox)
                    .build()
                NavigationLauncher.startNavigation(this@MainActivity, options)
            }
        }

        routingListener = object : RoutingListener {
            override fun onRoutingFailure(p0: RouteException?) {}

            override fun onRoutingStart() {
                Toast.makeText(this@MainActivity, "Finding Route...", Toast.LENGTH_SHORT).show()
            }

            override fun onRoutingSuccess(route: ArrayList<Route>, shortestRouteIndex: Int) {
                //add route(s) to the map using polyline
                val routeFirst = route[shortestRouteIndex]

                for (point in routeFirst.points) {
                    coordinates.add(Point.fromLngLat(point.latitude, point.longitude))
                }

                routeOptions = RouteOptions.builder()
                    .coordinates(coordinates)
                    .accessToken(Mapbox.getAccessToken()!!)
                    .baseUrl("https://api.mapbox.com")
                    .requestUuid("uuid")
                    .geometries(DirectionsCriteria.GEOMETRY_POLYLINE6)
                    .profile("hello")
                    .user("user")
                    .build()

                findWay(originPosition, destinationPosition)
            }

            override fun onRoutingCancelled() {}
        }
    }

    private fun autoComplete() {
        val apiKey = getString(R.string.map_api_key)
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }

        // Initialize the AutocompleteSupportFragment.
        val autocompleteFragment =
            (supportFragmentManager.findFragmentById(R.id.autocomplete_fragment) as AutocompleteSupportFragment?)!!
        autocompleteFragment.setPlaceFields(
            listOf(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.LAT_LNG
            )
        )

        // Set up a PlaceSelectionListener to handle the response.
        autocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                mapView!!.getMapAsync { googleMap ->
                    map = googleMap

                    if (symbol != null) {
                        symbolManager!!.delete(symbol)
                    }

                    if (navigationMapRoute != null) {
                        navigationMapRoute?.updateRouteVisibilityTo(false)
                    } else {
                        navigationMapRoute = NavigationMapRoute(null, mapView, map!!)
                    }

                    checkLocation()

                    originPosition =
                        Point.fromLngLat(originLocation!!.longitude, originLocation!!.latitude)
                    val startLocation = com.google.android.gms.maps.model.LatLng(
                        originLocation!!.latitude,
                        originLocation!!.longitude
                    )
                    start = startLocation

                    destinationPosition =
                        Point.fromLngLat(place.latLng!!.longitude, place.latLng!!.latitude)
                    val destinationLocationAutocomplete = LatLng(
                        destinationPosition!!.latitude(),
                        destinationPosition!!.longitude()
                    )
                    end = place.latLng

                    map!!.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            destinationLocationAutocomplete,
                            15.0
                        )
                    )

                    addPlaceMarker(destinationLocationAutocomplete)

                    findRoutes(start, end)
                    btnSearch!!.isEnabled = true
                }
            }

            override fun onError(status: Status) {}
        })
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        map = mapboxMap
        // Setup map listener
        map!!.addOnMapClickListener(this)
        mapboxMap.setStyle(
            Style.Builder().fromUri("mapbox://styles/mapbox/streets-v11")
        ) {
            // Map is set up and the style has loaded. Now you can add data or make other map adjustments
            enableLocation(it)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun enableLocation(loadedMapStyle: Style) {
        // Check if permissions are enabled and if not request
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            // Create and customize the LocationComponent's options
            val customLocationComponentOptions = LocationComponentOptions.builder(this)
                .elevation(5F)
                .accuracyAlpha(.6f)
                .trackingGesturesManagement(true)
                .accuracyColor(ContextCompat.getColor(this, R.color.colorGreen))
                .build()

            val locationComponentActivationOptions =
                LocationComponentActivationOptions.builder(this, loadedMapStyle)
                    .locationComponentOptions(customLocationComponentOptions)
                    .build()

            locationComponent = map!!.locationComponent

            locationComponent!!.activateLocationComponent(locationComponentActivationOptions)

            locationComponent!!.isLocationComponentEnabled = true

            locationComponent!!.cameraMode = CameraMode.TRACKING

            locationComponent!!.renderMode = RenderMode.COMPASS

            // Setup the symbol manager object
            symbolManager = SymbolManager(mapView, map!!, loadedMapStyle)

            // set non-data-driven properties, such as:
            symbolManager?.iconAllowOverlap = true
            symbolManager?.iconTranslate = arrayOf(-4f, 5f)
            symbolManager?.iconRotationAlignment = ICON_ROTATION_ALIGNMENT_VIEWPORT

            val bm: Bitmap =
                BitmapFactory.decodeResource(resources, R.drawable.mapbox_marker_icon_default)
            map!!.style?.addImage("place-marker", bm)
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (symbol != null) {
            symbolManager!!.delete(symbol)
        }

        if (navigationMapRoute != null) {
            navigationMapRoute?.updateRouteVisibilityTo(false)
        } else {
            navigationMapRoute = NavigationMapRoute(null, mapView, map!!)
        }

        addPlaceMarker(point)
        checkLocation()

        destinationPosition = Point.fromLngLat(point.longitude, point.latitude)
        originPosition = Point.fromLngLat(originLocation!!.longitude, originLocation!!.latitude)

        val startLocation = com.google.android.gms.maps.model.LatLng(
            originLocation!!.latitude,
            originLocation!!.longitude
        )
        val endLocation = com.google.android.gms.maps.model.LatLng(point.latitude, point.longitude)
        start = startLocation
        end = endLocation

        findRoutes(start, end)
        btnSearch.isEnabled = true

        return false
    }

    private fun findWay(origin: Point?, destination: Point?) {
        NavigationRoute.builder(this)
            .accessToken(Mapbox.getAccessToken()!!)
            .origin(origin!!)
            .destination(destination!!)
            .build()
            .getRoute(object : Callback<DirectionsResponse?> {
                override fun onResponse(
                    call: Call<DirectionsResponse?>,
                    response: Response<DirectionsResponse?>
                ) {
                    if (navigationMapRoute != null) {
                        navigationMapRoute?.updateRouteVisibilityTo(false)
                    } else {
                        navigationMapRoute = NavigationMapRoute(null, mapView, map!!)
                    }

                    directionRouteByMapBox = response.body()!!.routes()[0]
                    directionRouteByGoogle = DirectionsRoute.builder()
                        .routeOptions(routeOptions)
                        .distance(directionRouteByMapBox!!.distance())
                        .duration(directionRouteByMapBox!!.duration())
                        .geometry(directionRouteByMapBox!!.geometry())
                        .legs(directionRouteByMapBox!!.legs())
                        .voiceLanguage("en-US")
                        .weightName("auto")
                        .build()

                    if (directionRouteByGoogle != null) {
                        navigationMapRoute?.addRoute(directionRouteByGoogle)
                    }
                }

                override fun onFailure(call: Call<DirectionsResponse?>, t: Throwable) {}
            })
    }

    private fun findRoutes(
        Start: com.google.android.gms.maps.model.LatLng?,
        End: com.google.android.gms.maps.model.LatLng?
    ) {
        if (Start == null || End == null) {
            Toast.makeText(this@MainActivity, "Unable to get location", Toast.LENGTH_LONG).show()
        } else {
            val routing = Routing.Builder()
                .travelMode(AbstractRouting.TravelMode.DRIVING)
                .withListener(routingListener)
                .alternativeRoutes(true)
                .waypoints(Start, End)
                .key("AIzaSyCZl9xyKiDd6phqSGmy2gJhDbzSbtN9trU") //also define your api key here.
                .build()
            routing.execute()
        }
    }

    override fun onExplanationNeeded(permissionsToExplain: MutableList<String>?) {
        TODO("Not yet implemented")
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            map!!.setStyle(
                Style.Builder().fromUri("mapbox://styles/mapbox/streets-v11")
            ) {
                // Map is set up and the style has loaded. Now you can add data or make other map adjustments
                enableLocation(it)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun addPlaceMarker(location: LatLng) {
        // Add symbol at specified lat/lng
        symbol = symbolManager?.create(
            SymbolOptions()
                .withLatLng(location)
                .withIconImage("place-marker")
                .withIconSize(1.0f)
        )
    }

    private fun checkLocation() {
        if (originLocation == null) {
            map!!.locationComponent.lastKnownLocation?.run {
                originLocation = this
            }
        }
    }
}
