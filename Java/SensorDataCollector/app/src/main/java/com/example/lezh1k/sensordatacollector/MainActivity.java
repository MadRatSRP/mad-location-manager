package com.example.lezh1k.sensordatacollector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Date;

enum InitSensorErrorFlag {
    SUCCESS(0),
    SENSOR_MANAGER_ERR(1),
    MISSED_ACCELEROMETER(1 << 1),
    MISSED_GYROSCOPE(1<<2),
    MISSED_MAGNETOMETER(1<<3);

    public final long flag;
    InitSensorErrorFlag(long statusFlagValue) {
        this.flag = statusFlagValue;
    }

    public static String toString(long val) {
        String res = "";
        if (val == SUCCESS.flag) return "Success";
        if ((val & SENSOR_MANAGER_ERR.flag) != 0)
            res += "Sensor manager error";
        if ((val & MISSED_ACCELEROMETER.flag) != 0)
            res += "Missed accelerometer";
        if ((val & MISSED_GYROSCOPE.flag) != 0)
            res += "Missed gyroscope";
        if ((val & MISSED_MAGNETOMETER.flag) != 0)
            res += "Missed magnetometer";
        return res;
    }
}
/*****************************************************************/

public class MainActivity extends AppCompatActivity implements LocationListener, SensorEventListener {

    class Calibration {
        final int measurementCalibrationCount;
        private int count = 0;

        double sigmaX = SigmaNotInitialized;
        static final double SigmaNotInitialized = -1.0;
        double sigmaY = SigmaNotInitialized;
        double sigmaZ = SigmaNotInitialized;
        double measurementsX[];
        double measurementsY[];
        double measurementsZ[];

        Calibration(int measurementCalibrationCount) {
            this.measurementCalibrationCount = measurementCalibrationCount;
            measurementsX = new double[measurementCalibrationCount];
            measurementsY = new double[measurementCalibrationCount];
            measurementsZ = new double[measurementCalibrationCount];
        }

        private double calculateSigma(double sigma, double[] calibrations) {
            if (sigma != SigmaNotInitialized) return sigma;
            double sum = sigma = 0.0;
            for (int i = 0; i < measurementCalibrationCount; ++i) {
                sum += calibrations[i];
            }
            sum /= measurementCalibrationCount;

            for (int i = 0; i < measurementCalibrationCount; ++i) {
                sigma += Math.pow(calibrations[i] - sum, 2.0);
            }

            sigma /= measurementCalibrationCount;
            return sigma;
        }

        void Measure(double x, double y, double z) {
            if (count < measurementCalibrationCount) {
                measurementsX[count] = x;
                measurementsY[count] = y;
                measurementsZ[count] = z;
                ++count;
            } else {
                sigmaX = calculateSigma(sigmaX, measurementsX);
                sigmaY = calculateSigma(sigmaY, measurementsY);
                sigmaZ = calculateSigma(sigmaZ, measurementsZ);
            }
        }
    }
    /*********************************************************/

    class RefreshTask extends AsyncTask {

        public boolean needTerminate = false;
        @Override
        protected Object doInBackground(Object[] objects) {
            while (!needTerminate) {
                try {
                    Thread.sleep(200);
                    publishProgress();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        float[] m_rotationM = new float[9];
        float[] m_inclinationM = new float[9];
        float[] m_orientation = new float[3];

        float[] m_currAcc = new float[3];
        float[] m_currMag = new float[3];

        @Override
        protected void onProgressUpdate(Object... values) {
            if (m_accData == null || m_magData == null) return;
            System.arraycopy(m_accData, 0, m_currAcc, 0, 3);
            System.arraycopy(m_magData, 0, m_currMag, 0, 3);
            if (!SensorManager.getRotationMatrix(m_rotationM, m_inclinationM, m_currAcc, m_currMag)) {
                //todo something
                return;
            }

            Matrix m1 = new Matrix(3, 3);
            Matrix m1T = new Matrix(3, 3);
            m1.Set(m_rotationM);
            Matrix m2 = new Matrix(3, 1);
            m2.Set(m_currAcc);

            /*remove g component from acceleration*/
            m2.data[0][0] -= SensorManager.GRAVITY_EARTH*m_rotationM[6];
            m2.data[1][0] -= SensorManager.GRAVITY_EARTH*m_rotationM[7];
            m2.data[2][0] -= SensorManager.GRAVITY_EARTH*m_rotationM[8];

            Matrix m3 = new Matrix (3, 1);
            Matrix.MatrixTranspose(m1, m1T); //we got inverse matrix here
            Matrix.MatrixMultiply(m1T, m2, m3);

            double nn = m3.data[0][0] * Math.cos(m_currentMagneticDeclimation) +
                    m3.data[1][0] * Math.sin(m_currentMagneticDeclimation);
            double ne = m3.data[1][0] * Math.cos(m_currentMagneticDeclimation) -
                    m3.data[0][0] * Math.sin(m_currentMagneticDeclimation);
            m3.data[0][0] = nn;
            m3.data[1][0] = ne;

            String str = String.format("" +
                    "MDecl:%f, Lat:%f, Lon:%f, Alt:%f\n" +
                    "AccX=%f, AccY=%f, AccZ=%f\n" +
                    "AccN=%f, AccE=%f, AccU=%f",
                    m_currentMagneticDeclimation, m_currentLat, m_currentLon, m_currentAlt,
                    m_currAcc[0], m_currAcc[1], m_currAcc[2],
                    m3.data[0][0], m3.data[1][0], m3.data[2][0]);
            m_tvLocationData.setText(str);
        }
    }
    /*********************************************************/

    static void lowPassFilter(float alpha, float[] prev, float[] measured) {
        for (int i = 0; i < prev.length; ++i) {
            prev[i] += alpha * (measured[i] - prev[i]);
        }
    }

    private LocationManager m_locationManager;
    private SensorManager m_sensorManager;
    private Sensor m_accelerometer;
    private Sensor m_gyroscope;
    private Sensor m_magnetometer;

    private TextView m_tvStatus;
    private TextView m_tvAccelerometer;
    private TextView m_tvAccelerometerData;
    private TextView m_tvGyroscope;
    private TextView m_tvGyroscopeData;
    private TextView m_tvMagnetometer;
    private TextView m_tvMagnetometerData;
    private TextView m_tvLocationData;

    private float m_accData[] = new float[3];
    private float m_gyrData[] = new float[3];
    private float m_magData[] = new float[3];

    private float m_currentMagneticDeclimation = 0.0f;
    private float m_currentLat = 0.0f;
    private float m_currentLon = 0.0f;
    private float m_currentAlt = 0.0f;
    private float m_currentSpeed;
    private float m_currentPitch = 0.0f;
    private float m_currentYaw = 0.0f;
    private float m_currentRoll = 0.0f;


    static String sensorDescription(Sensor s) {
        String res = "";
        res += "Res : " + s.getResolution() + "\n";
        res += "Min Del : " + s.getMinDelay() + "\n";
        return res;
    }

    private long initSensors() {
        m_sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (m_sensorManager == null) {
            return InitSensorErrorFlag.SENSOR_MANAGER_ERR.flag;
        }

        m_accelerometer = m_sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        m_gyroscope = m_sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        m_magnetometer = m_sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        Sensor toCheck[] = { m_accelerometer, m_gyroscope, m_magnetometer};
        InitSensorErrorFlag toCheckRes[] = { InitSensorErrorFlag.MISSED_ACCELEROMETER,
                InitSensorErrorFlag.MISSED_GYROSCOPE,
                InitSensorErrorFlag.MISSED_MAGNETOMETER};

        long result = InitSensorErrorFlag.SUCCESS.flag;
        for (int i = 0; i < 3; ++i) {
            if (toCheck[i] != null) continue;
            result |= toCheckRes[i].flag;
        }
        return result;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        m_tvStatus = (TextView) findViewById(R.id.tvStatus);
        m_tvAccelerometer = (TextView) findViewById(R.id.tvAccelerometer);
        m_tvAccelerometerData = (TextView) findViewById(R.id.tvAccelerometerData);
        m_tvGyroscope = (TextView) findViewById(R.id.tvGyroscope);
        m_tvGyroscopeData = (TextView) findViewById(R.id.tvGyroscopeData);
        m_tvMagnetometer = (TextView) findViewById(R.id.tvMagnetometer);
        m_tvMagnetometerData = (TextView) findViewById(R.id.tvMagnetometerData);
        m_tvLocationData = (TextView) findViewById(R.id.tvLocationData);

        long ir = initSensors();
        m_tvStatus.setText(InitSensorErrorFlag.toString(ir));
        if (ir != InitSensorErrorFlag.SUCCESS.flag) {
            return;
        }

        m_tvLocationData.setText("Don't know where we are");

        m_locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (m_locationManager == null) {
            m_tvStatus.setText("Couldn't get location manager");
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
        } else {
            m_locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, this);
        }
        m_tvAccelerometer.setText("Accelerometer :\n" + sensorDescription(m_accelerometer));
        m_tvMagnetometer.setText("Magnetometer :\n" + sensorDescription(m_magnetometer));
        m_tvGyroscope.setText("Gyroscope :\n" + sensorDescription(m_gyroscope));

        m_sensorManager.registerListener(this, m_accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        m_sensorManager.registerListener(this, m_gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        m_sensorManager.registerListener(this, m_magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        AsyncTask at = new RefreshTask();
        at.execute();
    }

    /*********************************************************/

    @Override
    public void onLocationChanged(Location lkl) {
        m_currentLat = (float) lkl.getLatitude();
        m_currentLon = (float) lkl.getLongitude();
        m_currentAlt = (float) lkl.getAltitude();
        m_currentSpeed = lkl.getSpeed();
        GeomagneticField gf = new GeomagneticField(m_currentLat, m_currentLon,
                m_currentAlt, System.currentTimeMillis());
        m_currentMagneticDeclimation = gf.getDeclination();
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {
    }

    Calibration accCalibration = new Calibration(300);
    Calibration gyrCalibration = new Calibration(150);
    Calibration magCalibration = new Calibration(300);

    long accTimeStamp = System.currentTimeMillis();

    @Override
    public void onSensorChanged(SensorEvent event) {
        TextView tv = null;
        String format = null;
        Calibration cl = null;
        float values[] = event.values;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD :
                format = "Acc = %d, Mx = %f, My = %f, Mz = %f\n";
                tv = m_tvMagnetometerData;
                cl = magCalibration;
                System.arraycopy(event.values, 0, m_magData, 0, 3);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                format = "Acc = %d, Ax = %f, Ay = %f, Az = %f\n";
                tv = m_tvAccelerometerData;
                cl = accCalibration;
                lowPassFilter(0.2f,m_accData, event.values);
                values = m_accData;
                break;
            case Sensor.TYPE_GYROSCOPE:
                format = "Acc = %d, Ax = %f, Ay = %f, Az = %f\n";
                tv = m_tvGyroscopeData;
                cl = gyrCalibration;
                break;
        }

        if (tv == null) return;
        if (cl == null) return;
        cl.Measure(event.values[0], event.values[1], event.values[2]);
        tv.setText(String.format(format, event.accuracy,
                values[0], values[1], values[2]) +
                String.format("Sx : %f, Sy = %f, Sz = %f", cl.sigmaX, cl.sigmaY, cl.sigmaZ));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }
}