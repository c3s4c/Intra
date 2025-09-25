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
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader.TileMode;
import android.os.Build;
import android.os.Build.VERSION_CODES;
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

  private static final int DATA_STROKE_WIDTH = 10;  // Display pixels
  private static final float BOTTOM_MARGIN_FRACTION = 0.05f;  // Space to reserve below y=0.
  private static final float RIGHT_MARGIN_FRACTION = 0.1f;  // Space to reserve right of t=0.

  private QueryTracker tracker;
  private Paint dataPaint;   // Paint for the QPS curve itself.
  private Paint pulsePaint;  // Paint for the radiating pulses that also serve as x-axis marks.
  private Paint glowPaint;   // Paint for glow effect
  private Paint fillPaint;   // Paint for filled area
  private Paint particlePaint; // Paint for particles
  private Paint sparklePaint; // Paint for sparkle effects

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

  // Particle system variables
  private Particle[] particles = new Particle[50];
  private Random random = new Random();

  // Sparkle effects
  private float[] sparkles = new float[20]; // x, y, size, alpha for each sparkle

  public HistoryGraph(Context context, AttributeSet attrs) {
    super(context, attrs);
    tracker = VpnController.getInstance().getTracker(context);

    int color = getResources().getColor(R.color.accent_good);

    // Initialize particles
    for (int i = 0; i < particles.length; i++) {
      particles[i] = new Particle();
    }
    
    // Initialize sparkles
    for (int i = 0; i < sparkles.length; i += 4) {
      sparkles[i] = -1; // Mark as inactive
    }

    // Main data paint with glow effect
    dataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dataPaint.setStrokeWidth(DATA_STROKE_WIDTH);
    dataPaint.setStyle(Paint.Style.STROKE);
    dataPaint.setStrokeCap(Paint.Cap.ROUND);
    dataPaint.setColor(color);

    // Glow effect paint
    glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    glowPaint.setStrokeWidth(DATA_STROKE_WIDTH + 2);
    glowPaint.setStyle(Paint.Style.STROKE);
    glowPaint.setStrokeCap(Paint.Cap.ROUND);
    glowPaint.setColor(color);
    if (Build.VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
      glowPaint.setMaskFilter(new BlurMaskFilter(5, BlurMaskFilter.Blur.NORMAL));
    }

    // Fill paint for area under curve
    fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    fillPaint.setStyle(Paint.Style.FILL);

    // Pulse paint
    pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    pulsePaint.setStrokeWidth(0);
    pulsePaint.setStyle(Paint.Style.STROKE);
    pulsePaint.setColor(color);

    // Particle paint
    particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    particlePaint.setStyle(Paint.Style.FILL);
    particlePaint.setColor(color);

    // Sparkle paint
    sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    sparklePaint.setStyle(Paint.Style.FILL);
    sparklePaint.setColor(0xFFFFFFFF); // White sparkles
  }

  /**
   * @param color ARGB color to use for the lines in subsequent frames
   */
  public void setColor(int color) {
    dataPaint.setColor(color);
    glowPaint.setColor(color);
    pulsePaint.setColor(color);
    particlePaint.setColor(color);
    updateDataShader(getWidth());
    updateFillShader(getWidth(), getHeight());
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
    updateDataShader(w);
    updateFillShader(w, h);
  }

  private void updateDataShader(int width) {
    int color = dataPaint.getColor();
    int glowColor = color | 0xFF000000; // Ensure full opacity for glow
    glowPaint.setShader(new LinearGradient(0, 0, width, 0,
        glowColor, glowColor & 0x20FFFFFF, TileMode.CLAMP));
  }

  private void updateFillShader(int width, int height) {
    int color = dataPaint.getColor();
    fillPaint.setShader(new LinearGradient(0, 0, 0, height,
        color & 0x60FFFFFF, color & 0x10FFFFFF, TileMode.CLAMP));
  }

  @Override
  protected void onMeasure(int w, int h) {
    int wMode = MeasureSpec.getMode(w);
    int width;
    if (wMode == MeasureSpec.AT_MOST || wMode == MeasureSpec.EXACTLY) {
      // Always fill the whole width.
      width = MeasureSpec.getSize(w);
    } else {
      width = getSuggestedMinimumWidth();
    }

    int hMode = MeasureSpec.getMode(h);
    int height;
    if (hMode == MeasureSpec.EXACTLY) {
      // Nothing we can do about it.
      height = MeasureSpec.getSize(h);
    } else {
      // Fill 80% of the height.
      int screenHeight = getResources().getDisplayMetrics().heightPixels;
      height = (int)(0.8 * screenHeight);
      if (hMode == MeasureSpec.AT_MOST) {
        height = Math.min(height, MeasureSpec.getSize(h));
      }
    }

    setMeasuredDimension(width, height);
  }

  // Draw filled area under the curve
  private void drawFilledArea(Canvas canvas, float xoffset, float yoffset, 
                            float xscale, float yscale) {
    if (curveIsEmpty) return;

    Path path = new Path();
    path.moveTo(xoffset, yoffset); // Start at bottom left
    
    for (int i = 0; i < curve.length; i++) {
      float x = i * xscale + xoffset;
      float y = curve[i] * yscale + yoffset;
      if (i == 0) {
        path.lineTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    
    // Close the path back to bottom right
    path.lineTo((curve.length - 1) * xscale + xoffset, yoffset);
    path.close();
    
    canvas.drawPath(path, fillPaint);
  }

  // Draw glow effect behind the main line
  private void drawGlowEffect(Canvas canvas, float[] lines) {
    if (curveIsEmpty) return;
    canvas.drawLines(lines, glowPaint);
  }

  // Draw particles that follow the curve peaks
  private void drawParticles(Canvas canvas, float xoffset, float yoffset, 
                           float xscale, float yscale) {
    if (curveIsEmpty) return;

    // Update particles
    for (Particle particle : particles) {
      if (!particle.active || particle.age > particle.lifetime) {
        // Spawn new particle at random peak
        if (random.nextFloat() < 0.3f) { // 30% chance each frame
          int peakIndex = findRandomPeak();
          if (peakIndex != -1) {
            particle.x = peakIndex * xscale + xoffset;
            particle.y = curve[peakIndex] * yscale + yoffset;
            particle.vx = (random.nextFloat() - 0.5f) * 2f;
            particle.vy = (random.nextFloat() - 0.5f) * 3f;
            particle.lifetime = random.nextInt(60) + 30;
            particle.age = 0;
            particle.active = true;
          }
        }
      }

      if (particle.active) {
        // Update position
        particle.x += particle.vx;
        particle.y += particle.vy;
        particle.vy += 0.1f; // Gravity
        particle.age++;
        
        // Draw particle
        float alpha = 1f - (float) particle.age / particle.lifetime;
        particlePaint.setAlpha((int) (alpha * 255));
        canvas.drawCircle(particle.x, particle.y, 2f, particlePaint);
      }
    }
  }

  // Find random peak in the curve for particle spawning
  private int findRandomPeak() {
    if (curveIsEmpty) return -1;
    
    // Find local maxima
    for (int attempt = 0; attempt < 10; attempt++) {
      int i = random.nextInt(curve.length - 2) + 1;
      if (curve[i] > curve[i-1] && curve[i] > curve[i+1] && curve[i] > max * 0.2f) {
        return i;
      }
    }
    return -1;
  }

  // Draw sparkling effects at high points
  private void drawSparkles(Canvas canvas, float xoffset, float yoffset,
                          float xscale, float yscale) {
    if (curveIsEmpty) return;

    for (int i = 0; i < sparkles.length; i += 4) {
      if (sparkles[i] == -1 && random.nextFloat() < 0.02f) {
        // Spawn new sparkle at high point
        int sparkleIndex = findHighPoint();
        if (sparkleIndex != -1) {
          sparkles[i] = sparkleIndex * xscale + xoffset; // x
          sparkles[i+1] = curve[sparkleIndex] * yscale + yoffset; // y
          sparkles[i+2] = random.nextFloat() * 3f + 1f; // size
          sparkles[i+3] = 1f; // alpha
        }
      }

      if (sparkles[i] != -1) {
        // Update and draw sparkle
        sparkles[i+3] -= 0.05f; // Fade out
        if (sparkles[i+3] <= 0) {
          sparkles[i] = -1; // Deactivate
        } else {
          sparklePaint.setAlpha((int) (sparkles[i+3] * 255));
          canvas.drawCircle(sparkles[i], sparkles[i+1], sparkles[i+2], sparklePaint);
          
          // Draw glow around sparkle
          sparklePaint.setAlpha((int) (sparkles[i+3] * 128));
          canvas.drawCircle(sparkles[i], sparkles[i+1], sparkles[i+2] * 2f, sparklePaint);
        }
      }
    }
  }

  private int findHighPoint() {
    if (curveIsEmpty) return -1;
    
    for (int i = 1; i < curve.length - 1; i++) {
      if (curve[i] > max * 0.5f && curve[i] > curve[i-1] && curve[i] > curve[i+1]) {
        return i;
      }
    }
    return -1;
  }

  // Draw ripple effects from the current point
  private void drawRippleEffects(Canvas canvas, float x, float y, float currentValue) {
    float intensity = currentValue / Math.max(max, 1f);
    
    for (int i = 0; i < 3; i++) {
      float radius = DATA_STROKE_WIDTH * (1 + i) * intensity;
      int alpha = (int) ((1f - i * 0.3f) * intensity * 100);
      pulsePaint.setAlpha(alpha);
      canvas.drawCircle(x, y, radius, pulsePaint);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    // Normally the coordinate system puts 0,0 at the top left.  This puts it at the bottom right,
    // with positive axes pointed up and left.
    canvas.rotate(180, getWidth() / 2, getHeight() / 2);

    // Scale factors based on current window size.
    float xoffset = (float) (getWidth()) * RIGHT_MARGIN_FRACTION;
    float usableWidth = getWidth() - xoffset;
    float yoffset = getHeight() * BOTTOM_MARGIN_FRACTION;
    // Make graph fit in the available height and never be taller than the width.
    float usableHeight = Math.min(getWidth(), getHeight() - (DATA_STROKE_WIDTH + yoffset));

    now = SystemClock.elapsedRealtime();
    float rightEndY;
    float xscale = usableWidth / (curve.length - 1);
    float yscale = max == 0 ? 0 : usableHeight / max;

    if (updateCurve()) {
      updateMax();
      yscale = max == 0 ? 0 : usableHeight / max;

      // Convert the curve into lines in the appropriate scale, and draw it.
      for (int i = 1; i < curve.length; ++i) {
        int j = (i - 1) * 4;
        lines[j] = (i - 1) * xscale + xoffset;
        lines[j + 1] = curve[i - 1] * yscale + yoffset;
        lines[j + 2] = i * xscale + xoffset;
        lines[j + 3] = curve[i] * yscale + yoffset;
      }

      // Draw all effects in order
      drawFilledArea(canvas, xoffset, yoffset, xscale, yscale);
      drawGlowEffect(canvas, lines);
      canvas.drawLines(lines, dataPaint);
      drawParticles(canvas, xoffset, yoffset, xscale, yscale);
      drawSparkles(canvas, xoffset, yoffset, xscale, yscale);
      
      rightEndY = lines[1];
    } else {
      max = 0;
      // Draw a horizontal line at y = 0.
      canvas.drawLine(xoffset, yoffset, xoffset + usableWidth, yoffset, dataPaint);
      rightEndY = yoffset;
    }

    // Draw enhanced circle at the right end of the line with ripple effects
    float tagRadius = 3 * DATA_STROKE_WIDTH;
    float tagX = xoffset - tagRadius;
    
    // Ripple effects from current point
    drawRippleEffects(canvas, tagX, rightEndY, curveIsEmpty ? 0 : curve[curve.length-1]);
    
    // Enhanced circle with glow
    glowPaint.setStrokeWidth(tagRadius * 2);
    canvas.drawCircle(tagX, rightEndY, tagRadius, glowPaint);
    canvas.drawCircle(tagX, rightEndY, tagRadius, dataPaint);

    // Draw pulses at regular intervals, growing and fading with age.
    float maxRadius = getWidth() - tagX;
    for (long age = now % PULSE_INTERVAL_MS; age < WINDOW_MS; age += PULSE_INTERVAL_MS) {
      float fraction = ((float) age) / WINDOW_MS;
      float radius = tagRadius + fraction * (maxRadius - tagRadius);
      int alpha = (int) (255 * (1 - fraction));
      pulsePaint.setAlpha(alpha);
      canvas.drawCircle(tagX, yoffset, radius, pulsePaint);
    }

    // Draw the next frame at the UI's preferred update frequency.
    postInvalidateOnAnimation();
  }

  // Particle class for particle system
  private class Particle {
    float x, y;
    float vx, vy;
    int age = 0;
    int lifetime = 0;
    boolean active = false;
  }
}
