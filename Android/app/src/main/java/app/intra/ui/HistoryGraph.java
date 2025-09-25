/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader.TileMode;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import app.intra.R;
import app.intra.sys.ActivityReceiver;
import app.intra.sys.QueryTracker;
import app.intra.sys.VpnController;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;


/**
 * A graph showing the DNS query activity over the past minute.  The sequence of events is
 * rendered as a QPS graph using a gradually diffusing gaussian convolution, reflecting the idea
 * that the more recent an event is, the more we care about the fine temporal detail.
 */
public class HistoryGraph extends View implements ActivityReceiver {

  private static final int WINDOW_MS = 60 * 1000;  // Show the last minute of activity
  private static final int RESOLUTION_MS = 100;  // Compute the QPS curve with 100ms granularity.
  private static final int PULSE_INTERVAL_MS = 10 * 1000;  // Mark a pulse every 10 seconds

  private static final int DATA_STROKE_WIDTH = 6;  // کاهش ضخامت خط
  private static final float BOTTOM_MARGIN_FRACTION = 0.05f;  // Space to reserve below y=0.
  private static final float RIGHT_MARGIN_FRACTION = 0.1f;  // Space to reserve right of t=0.

  private QueryTracker tracker;
  private Paint dataPaint;   // Paint for the QPS curve itself.
  private Paint pulsePaint;  // Paint for the radiating pulses that also serve as x-axis marks.
  private Paint glowPaint;   // Paint for subtle glow effect
  private Paint fillPaint;   // Paint for filled area
  private Paint particlePaint; // Paint for particles

  // Preallocate the curve to reduce garbage collection pressure.
  private int range = WINDOW_MS / RESOLUTION_MS;
  private float[] curve = new float[range];
  private float[] lines = new float[(curve.length - 1) * 4];

  // Indicate whether the current curve is all zero, which allows an optimization.
  private boolean curveIsEmpty = true;

  // The peak value of the graph.  This value rises as needed, then falls to zero when the graph
  // is empty.
  private float max = 0;

  // Value of SystemClock.elapsedRealtime() for the current frame.
  private long now;

  // Particle system variables - کاهش تعداد ذرات
  private Particle[] particles = new Particle[15]; // فقط 15 ذره
  private Random random = new Random();
  private long lastParticleUpdate = 0;
  private static final long PARTICLE_UPDATE_INTERVAL = 100; // آپدیت هر 100ms

  public HistoryGraph(Context context, AttributeSet attrs) {
    super(context, attrs);
    tracker = VpnController.getInstance().getTracker(context);

    int color = getResources().getColor(R.color.accent_good);

    // Initialize particles
    for (int i = 0; i < particles.length; i++) {
      particles[i] = new Particle();
    }

    // Main data paint - ساده‌تر
    dataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dataPaint.setStrokeWidth(DATA_STROKE_WIDTH);
    dataPaint.setStyle(Paint.Style.STROKE);
    dataPaint.setStrokeCap(Paint.Cap.ROUND);
    dataPaint.setColor(color);

    // Subtle glow effect - بسیار سبک
    glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    glowPaint.setStrokeWidth(DATA_STROKE_WIDTH + 3); // کاهش ضخامت
    glowPaint.setStyle(Paint.Style.STROKE);
    glowPaint.setStrokeCap(Paint.Cap.ROUND);
    glowPaint.setColor(color);
    glowPaint.setAlpha(80); // شفافیت بیشتر

    // Fill paint for area under curve
    fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setAlpha(40); // بسیار سبک

    // Pulse paint
    pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    pulsePaint.setStrokeWidth(1);
    pulsePaint.setStyle(Paint.Style.STROKE);
    pulsePaint.setColor(color);

    // Particle paint
    particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    particlePaint.setStyle(Paint.Style.FILL);
    particlePaint.setColor(color);
    particlePaint.setAlpha(150); // شفافیت متوسط
  }

  /**
   * @param color ARGB color to use for the lines in subsequent frames
   */
  public void setColor(int color) {
    dataPaint.setColor(color);
    glowPaint.setColor(color);
    pulsePaint.setColor(color);
    particlePaint.setColor(color);
    fillPaint.setColor(color);
    updateShaders(getWidth(), getHeight());
  }

  // Gaussian curve formula.  (Not normalized.)
  private static float gaussian(float mu, float inverseSigma, int x) {
    float z = (x - mu) * inverseSigma;
    return ((float) Math.exp(-z * z)) * inverseSigma;
  }

  @Override
  public void receive(Collection<Long> activity) {
    // Reset the curve, and populate it if there are any events in the window.
    curveIsEmpty = true;
    float scale = 1.0f / RESOLUTION_MS;
    for (long t : activity) {
      long age = now - t;
      if (age < 0) {
        // Possible clock skew mishap.
        continue;
      }
      float e = age * scale;

      // Diffusion equation: sigma grows as sqrt(time).
      // Add a constant to avoid dividing by zero.
      float sigma = (float) Math.sqrt(e + DATA_STROKE_WIDTH);

      // Optimization: truncate the Gaussian at +/- 2.7 sigma.  Beyond 2.7 sigma, a gaussian is less
      // than 1/1000 of its peak value, which is not visible on our graph.
      float support = 2.7f * sigma;
      int left = Math.max(0, (int) (e - support));
      if (left > range) {
        // This event is offscreen.
        continue;
      }
      if (curveIsEmpty) {
        curveIsEmpty = false;
        Arrays.fill(curve, 0.0f);
      }
      int right = Math.min(range, (int) (e + support));
      float inverseSigma = 1.0f / sigma;  // Optimization: only compute division once per event.
      for (int i = left; i < right; ++i) {
        curve[i] += gaussian(e, inverseSigma, i);
      }
    }
  }

  // Updates the curve contents or returns false if there are no contents.
  private boolean updateCurve() {
    tracker.showActivity(this);
    return !curveIsEmpty;
  }

  private void updateMax() {
    // Increase the maximum to fit the curve.
    float total = 0;
    for (float v : curve) {
      total += v;
      max = Math.max(max, v);
    }
    // Set the maximum to zero if the curve is all zeros.
    if (total == 0) {
      max = 0;
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    updateShaders(w, h);
  }

  private void updateShaders(int width, int height) {
    int color = dataPaint.getColor();
    
    // گرادیان ساده برای خط اصلی
    dataPaint.setShader(new LinearGradient(0, 0, width, 0,
        color, color, TileMode.CLAMP)); // بدون گرادیان پیچیده
        
    // گرادیان سبک برای پرکردن
    fillPaint.setShader(new LinearGradient(0, 0, 0, height,
        color & 0x28FFFFFF, color & 0x08FFFFFF, TileMode.CLAMP));
  }

  @Override
  protected void onMeasure(int w, int h) {
    int wMode = MeasureSpec.getMode(w);
    int width;
    if (wMode == MeasureSpec.AT_MOST || wMode == MeasureSpec.EXACTLY) {
      width = MeasureSpec.getSize(w);
    } else {
      width = getSuggestedMinimumWidth();
    }

    int hMode = MeasureSpec.getMode(h);
    int height;
    if (hMode == MeasureSpec.EXACTLY) {
      height = MeasureSpec.getSize(h);
    } else {
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      height = (int)(0.8 * screenHeight);
      if (hMode == MeasureSpec.AT_MOST) {
        height = Math.min(height, MeasureSpec.getSize(h));
      }
    }

    setMeasuredDimension(width, height);
  }

  // Draw filled area under the curve - ساده‌تر
  private void drawFilledArea(Canvas canvas, float xoffset, float yoffset, 
                            float xscale, float yscale) {
    if (curveIsEmpty || max == 0) return;

    Path path = new Path();
    path.moveTo(xoffset, yoffset);
    
    for (int i = 0; i < curve.length; i++) {
      float x = i * xscale + xoffset;
      float y = curve[i] * yscale + yoffset;
      if (i == 0) {
        path.lineTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    
    path.lineTo((curve.length - 1) * xscale + xoffset, yoffset);
    path.close();
    
    canvas.drawPath(path, fillPaint);
  }

  // Draw subtle glow effect - بسیار سبک
  private void drawGlowEffect(Canvas canvas, float[] lines) {
    if (curveIsEmpty) return;
    
    // فقط برای بخش‌های پرترافیک glow بکش
    boolean hasSignificantData = false;
    for (float value : curve) {
      if (value > max * 0.1f) {
        hasSignificantData = true;
        break;
      }
    }
    
    if (hasSignificantData) {
      canvas.drawLines(lines, glowPaint);
    }
  }

  // Draw lightweight particles - بهینه‌شده
  private void drawParticles(Canvas canvas, float xoffset, float yoffset, 
                           float xscale, float yscale) {
    if (curveIsEmpty) return;

    long currentTime = SystemClock.elapsedRealtime();
    if (currentTime - lastParticleUpdate < PARTICLE_UPDATE_INTERVAL) {
      return; // آپدیت کمتر برای عملکرد بهتر
    }
    lastParticleUpdate = currentTime;

    for (Particle particle : particles) {
      if (!particle.active) {
        // شانس کمتری برای ایجاد ذره جدید
        if (random.nextFloat() < 0.1f) {
          int peakIndex = findRandomPeak();
          if (peakIndex != -1) {
            particle.x = peakIndex * xscale + xoffset;
            particle.y = curve[peakIndex] * yscale + yoffset;
            particle.vx = (random.nextFloat() - 0.5f) * 1.5f;
            particle.vy = (random.nextFloat() - 0.5f) * 2f;
            particle.lifetime = random.nextInt(40) + 20;
            particle.age = 0;
            particle.active = true;
          }
        }
      }

      if (particle.active) {
        particle.x += particle.vx;
        particle.y += particle.vy;
        particle.vy += 0.05f; // جاذبه سبک‌تر
        particle.age++;
        
        if (particle.age > particle.lifetime) {
          particle.active = false;
        } else {
          float alpha = 1f - (float) particle.age / particle.lifetime;
          particlePaint.setAlpha((int) (alpha * 120)); // شفافیت کمتر
          canvas.drawCircle(particle.x, particle.y, 1.5f, particlePaint); // ذرات کوچک‌تر
        }
      }
    }
  }

  private int findRandomPeak() {
    if (curveIsEmpty) return -1;
    
    // فقط نقاط واقعاً پرترافیک
    for (int attempt = 0; attempt < 5; attempt++) {
      int i = random.nextInt(curve.length - 2) + 1;
      if (curve[i] > max * 0.3f && curve[i] > curve[i-1] && curve[i] > curve[i+1]) {
        return i;
      }
    }
    return -1;
  }

  // Draw simple ripple effects - ساده‌تر
  private void drawRippleEffects(Canvas canvas, float x, float y, float currentValue) {
    if (curveIsEmpty || max == 0) return;
    
    float intensity = Math.min(currentValue / max, 1f);
    if (intensity < 0.2f) return; // فقط برای ترافیک قابل توجه
    
    for (int i = 0; i < 2; i++) { // فقط 2 موج
      float radius = DATA_STROKE_WIDTH * (1 + i) * intensity * 0.5f;
      int alpha = (int) ((1f - i * 0.5f) * intensity * 60);
      pulsePaint.setAlpha(alpha);
      pulsePaint.setStrokeWidth(1);
      canvas.drawCircle(x, y, radius, pulsePaint);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.rotate(180, getWidth() / 2, getHeight() / 2);

    float xoffset = (float) (getWidth()) * RIGHT_MARGIN_FRACTION;
    float usableWidth = getWidth() - xoffset;
    float yoffset = getHeight() * BOTTOM_MARGIN_FRACTION;
    float usableHeight = Math.min(getWidth(), getHeight() - (DATA_STROKE_WIDTH + yoffset));

    now = SystemClock.elapsedRealtime();
    float rightEndY;
    float xscale = usableWidth / (curve.length - 1);
    float yscale = max == 0 ? 0 : usableHeight / max;

    if (updateCurve()) {
      updateMax();
      yscale = max == 0 ? 0 : usableHeight / max;

      for (int i = 1; i < curve.length; ++i) {
        int j = (i - 1) * 4;
        lines[j] = (i - 1) * xscale + xoffset;
        lines[j + 1] = curve[i - 1] * yscale + yoffset;
        lines[j + 2] = i * xscale + xoffset;
        lines[j + 3] = curve[i] * yscale + yoffset;
      }

      // ترسیم بهینه‌شده با افکت‌های سبک
      drawFilledArea(canvas, xoffset, yoffset, xscale, yscale);
      drawGlowEffect(canvas, lines);
      canvas.drawLines(lines, dataPaint);
      drawParticles(canvas, xoffset, yoffset, xscale, yscale);
      
      rightEndY = lines[1];
    } else {
      max = 0;
      canvas.drawLine(xoffset, yoffset, xoffset + usableWidth, yoffset, dataPaint);
      rightEndY = yoffset;
    }

    // نقطه انتهایی ساده‌تر
    float tagRadius = 2 * DATA_STROKE_WIDTH;
    float tagX = xoffset - tagRadius;
    
    drawRippleEffects(canvas, tagX, rightEndY, curveIsEmpty ? 0 : curve[curve.length-1]);
    
    // دایره ساده بدون افکت‌های سنگین
    canvas.drawCircle(tagX, rightEndY, tagRadius, dataPaint);

    // پالس‌های سبک‌تر
    float maxRadius = getWidth() - tagX;
    for (long age = now % PULSE_INTERVAL_MS; age < WINDOW_MS; age += PULSE_INTERVAL_MS) {
      float fraction = ((float) age) / WINDOW_MS;
      float radius = tagRadius + fraction * (maxRadius - tagRadius);
      int alpha = (int) (180 * (1 - fraction)); // شفافیت کمتر
      pulsePaint.setAlpha(alpha);
      canvas.drawCircle(tagX, yoffset, radius, pulsePaint);
    }

    postInvalidateOnAnimation();
  }

  // Particle class ساده‌شده
  private class Particle {
    float x, y;
    float vx, vy;
    int age = 0;
    int lifetime = 0;
    boolean active = false;
  }
}
