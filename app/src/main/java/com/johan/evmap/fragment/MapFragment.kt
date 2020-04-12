package com.johan.evmap.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.*
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.snackbar.Snackbar
import com.johan.evmap.GalleryActivity
import com.johan.evmap.MapsActivity
import com.johan.evmap.R
import com.johan.evmap.REQUEST_LOCATION_PERMISSION
import com.johan.evmap.adapter.ConnectorAdapter
import com.johan.evmap.adapter.DetailAdapter
import com.johan.evmap.adapter.GalleryAdapter
import com.johan.evmap.api.ChargeLocation
import com.johan.evmap.api.ChargeLocationCluster
import com.johan.evmap.api.ChargepointListItem
import com.johan.evmap.api.ChargerPhoto
import com.johan.evmap.databinding.FragmentMapBinding
import com.johan.evmap.ui.ClusterIconGenerator
import com.johan.evmap.ui.getBitmapDescriptor
import com.johan.evmap.viewmodel.MapPosition
import com.johan.evmap.viewmodel.MapViewModel
import com.johan.evmap.viewmodel.viewModelFactory
import com.mahc.custombottomsheetbehavior.BottomSheetBehaviorGoogleMapsLike
import kotlinx.android.synthetic.main.fragment_map.*

class MapFragment : Fragment(), OnMapReadyCallback, MapsActivity.FragmentCallback {
    private lateinit var binding: FragmentMapBinding
    private val vm: MapViewModel by viewModels(factoryProducer = {
        viewModelFactory { MapViewModel(getString(R.string.goingelectric_key)) }
    })
    private var map: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var bottomSheetBehavior: BottomSheetBehaviorGoogleMapsLike<View>
    private var markers: Map<Marker, ChargeLocation> = emptyMap()
    private var clusterMarkers: List<Marker> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_map, container, false)
        binding.lifecycleOwner = this
        binding.vm = vm

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        setHasOptionsMenu(true)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        bottomSheetBehavior = BottomSheetBehaviorGoogleMapsLike.from(binding.bottomSheet)

        setupObservers()
        setupClickListeners()
        setupAdapters()

        val navController = findNavController()
        (activity as? MapsActivity)?.setSupportActionBar(binding.toolbar)
        binding.toolbar.setupWithNavController(
            navController,
            (requireActivity() as MapsActivity).appBarConfiguration
        )
    }

    override fun onResume() {
        super.onResume()
        val hostActivity = activity as? MapsActivity ?: return
        hostActivity.fragmentCallback = this
    }

    override fun onPause() {
        super.onPause()
        val hostActivity = activity as? MapsActivity ?: return
        hostActivity.fragmentCallback = null
    }

    private fun setupClickListeners() {
        binding.fabLocate.setOnClickListener {
            if (!hasLocationPermission()) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_LOCATION_PERMISSION
                )
            } else {
                enableLocation(true)
            }
        }
        binding.fabDirections.setOnClickListener {
            val charger = vm.charger.value
            if (charger != null) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    (requireActivity() as MapsActivity).navigateTo(charger)
                }
            }
        }
        binding.detailView.goingelectricButton.setOnClickListener {
            val charger = vm.charger.value
            if (charger != null) {
                (activity as? MapsActivity)?.openUrl("https:${charger.url}")
            }
        }
        binding.detailView.topPart.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehaviorGoogleMapsLike.STATE_ANCHOR_POINT
        }
    }

    private fun setupObservers() {
        vm.charger.observe(viewLifecycleOwner, object : Observer<ChargeLocation> {
            var previousCharger = vm.charger.value

            override fun onChanged(charger: ChargeLocation?) {
                if (charger != null) {
                    if (previousCharger == null || previousCharger!!.id != charger.id) {
                        bottomSheetBehavior.state =
                            BottomSheetBehaviorGoogleMapsLike.STATE_COLLAPSED
                    }
                } else {
                    bottomSheetBehavior.state = BottomSheetBehaviorGoogleMapsLike.STATE_HIDDEN
                }
                previousCharger = charger
            }
        })
        vm.chargepoints.observe(viewLifecycleOwner, Observer {
            updateMap(it)
        })
    }

    private fun setupAdapters() {
        val galleryClickListener = object : GalleryAdapter.ItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                val photos = vm.charger.value?.photos ?: return
                val intent = Intent(context, GalleryActivity::class.java).apply {
                    putExtra(GalleryActivity.EXTRA_PHOTOS, ArrayList<ChargerPhoto>(photos))
                    putExtra(GalleryActivity.EXTRA_POSITION, position)
                }
                // TODO:
                /*val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    activity, view, view.transitionName
                )
                startActivity(intent, options.toBundle())*/
                startActivity(intent)
            }
        }

        binding.gallery.apply {
            adapter = GalleryAdapter(context, galleryClickListener)
            layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            addItemDecoration(
                DividerItemDecoration(
                    context, LinearLayoutManager.HORIZONTAL
                ).apply {
                    setDrawable(context.getDrawable(R.drawable.gallery_divider)!!)
                })
        }

        binding.detailView.connectors.apply {
            adapter = ConnectorAdapter()
            layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }

        binding.detailView.details.apply {
            adapter = DetailAdapter()
            layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            addItemDecoration(
                DividerItemDecoration(
                    context,
                    LinearLayoutManager.VERTICAL
                )
            )
        }
    }

    override fun onMapReady(map: GoogleMap) {
        this.map = map
        map.setOnCameraIdleListener {
            vm.mapPosition.value = MapPosition(
                map.projection.visibleRegion.latLngBounds, map.cameraPosition.zoom
            )
        }
        map.setOnMarkerClickListener { marker ->
            when (marker) {
                in markers -> {
                    vm.chargerSparse.value = markers[marker]
                    true
                }
                in clusterMarkers -> {
                    val newZoom = map.cameraPosition.zoom + 2
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, newZoom))
                    true
                }
                else -> false
            }
        }
        map.setOnMapClickListener {
            vm.chargerSparse.value = null
        }

        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        map.setMapStyle(
            if (mode == Configuration.UI_MODE_NIGHT_YES) {
                MapStyleOptions.loadRawResourceStyle(context, R.raw.maps_night_mode)
            } else null
        )


        if (hasLocationPermission()) {
            enableLocation(false)
        } else {
            // center the camera on Europe
            val cameraUpdate = CameraUpdateFactory.newLatLngZoom(LatLng(50.113388, 9.252536), 3.5f)
            map.moveCamera(cameraUpdate)
        }

        vm.mapPosition.value = MapPosition(
            map.projection.visibleRegion.latLngBounds, map.cameraPosition.zoom
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(animate: Boolean) {
        val map = this.map ?: return
        map.isMyLocationEnabled = true
        vm.myLocationEnabled.value = true
        map.uiSettings.isMyLocationButtonEnabled = false
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                val camUpdate = CameraUpdateFactory.newLatLngZoom(latLng, 13f)
                if (animate) {
                    map.animateCamera(camUpdate)
                } else {
                    map.moveCamera(camUpdate)
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun updateMap(chargepoints: List<ChargepointListItem>) {
        val map = this.map ?: return
        markers.keys.forEach { it.remove() }
        clusterMarkers.forEach { it.remove() }

        val context = context ?: return
        val iconGenerator = ClusterIconGenerator(context)
        val clusters = chargepoints.filterIsInstance<ChargeLocationCluster>()
        val chargers = chargepoints.filterIsInstance<ChargeLocation>()

        markers = chargers.map { charger ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(charger.coordinates.lat, charger.coordinates.lng))
                    .icon(
                        getBitmapDescriptor(
                            R.drawable.ic_map_marker_charging, when {
                                charger.maxPower >= 100 -> R.color.charger_100kw
                                charger.maxPower >= 43 -> R.color.charger_43kw
                                charger.maxPower >= 20 -> R.color.charger_20kw
                                charger.maxPower >= 11 -> R.color.charger_11kw
                                else -> R.color.charger_low
                            }, context
                        )
                    )
            ) to charger
        }.toMap()
        clusterMarkers = clusters.map { cluster ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(cluster.coordinates.lat, cluster.coordinates.lng))
                    .icon(BitmapDescriptorFactory.fromBitmap(iconGenerator.makeIcon(cluster.clusterCount.toString())))
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_LOCATION_PERMISSION -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    enableLocation(true)
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.map, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_filter -> {
                Snackbar.make(root, R.string.not_implemented, Snackbar.LENGTH_SHORT).show()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun goBack(): Boolean {
        return if (bottomSheetBehavior.state != BottomSheetBehaviorGoogleMapsLike.STATE_COLLAPSED &&
            bottomSheetBehavior.state != BottomSheetBehaviorGoogleMapsLike.STATE_HIDDEN
        ) {
            bottomSheetBehavior.state = BottomSheetBehaviorGoogleMapsLike.STATE_COLLAPSED
            true
        } else if (bottomSheetBehavior.state == BottomSheetBehaviorGoogleMapsLike.STATE_COLLAPSED) {
            vm.chargerSparse.value = null
            true
        } else {
            false
        }
    }


    companion object {
        const val EXTRA_STARTING_GALLERY_POSITION = "extra_starting_item_position"
        const val EXTRA_CURRENT_GALLERY_POSITION = "extra_current_item_position"
    }

    override fun getRootView(): CoordinatorLayout {
        return root
    }
}