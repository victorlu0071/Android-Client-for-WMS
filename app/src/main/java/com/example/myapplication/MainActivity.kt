package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.myapplication.api.NetworkModule
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.ui.addproduct.AddProductActivity
import com.example.myapplication.ui.movestock.MoveStockFragment
import com.example.myapplication.ui.productinfo.ProductInfoFragment
import com.example.myapplication.ui.scanner.CustomBarcodeScannerActivity
import com.example.myapplication.ui.stock.StockFragment
import com.example.myapplication.util.PreferencesManager
import com.example.myapplication.util.SoundUtil
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize NetworkModule with application context
        NetworkModule.initialize(applicationContext)
        preferencesManager = PreferencesManager.getInstance(applicationContext)
        
        // Initialize sound effects
        SoundUtil.initialize()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)

        binding.appBarMain.fab?.setOnClickListener { view ->
            // Launch the AddProductActivity
            val intent = Intent(this, AddProductActivity::class.java)
            startActivity(intent)
        }

        val navHostFragment =
            (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment?)!!
        val navController = navHostFragment.navController

        binding.navView?.let {
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_product_info, R.id.nav_stock, R.id.nav_move_stock, R.id.nav_settings
                ),
                binding.drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            it.setupWithNavController(navController)
        }

        binding.appBarMain.contentMain.bottomNavView?.let {
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_product_info, R.id.nav_stock, R.id.nav_move_stock
                )
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            it.setupWithNavController(navController)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if this key matches the bound scan button
        val boundScanKey = preferencesManager.getScanButtonKeyCode()
        
        if (boundScanKey != null && keyCode == boundScanKey) {
            // Handle based on which fragment is currently active
            val currentFragment = getCurrentFragment()
            
            when (currentFragment) {
                is ProductInfoFragment -> {
                    // Launch the barcode scanner from Product Info fragment
                    launchBarcodeScanner()
                    return true
                }
                is StockFragment -> {
                    // Launch the barcode scanner from Stock fragment
                    launchBarcodeScanner()
                    return true
                }
                is MoveStockFragment -> {
                    // Launch the barcode scanner from Move Stock fragment
                    launchBarcodeScanner()
                    return true
                }
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun getCurrentFragment(): Any? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
    }
    
    private fun launchBarcodeScanner() {
        // Clear any previous barcode data before launching the scanner
        com.example.myapplication.util.BarcodeEvent.clearLastBarcode()
        val intent = Intent(this, CustomBarcodeScannerActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val result = super.onCreateOptionsMenu(menu)
        // Using findViewById because NavigationView exists in different layout files
        // between w600dp and w1240dp
        val navView: NavigationView? = findViewById(R.id.nav_view)
        if (navView == null) {
            // The navigation drawer already has the items including the items in the overflow menu
            // We only inflate the overflow menu if the navigation drawer isn't visible
            menuInflater.inflate(R.menu.overflow, menu)
        }
        return result
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_settings -> {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                navController.navigate(R.id.nav_settings)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release sound resources when the app is closing
        SoundUtil.release()
    }
}