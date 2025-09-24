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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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

  private static final int DATA_STROKE_WIDTH = 10;  // Display pixels
  private static final float BOTTOM_MARGIN_FRACTION = 0.05f;  // Space to reserve below y=0.
  private static final float RIGHT_MARGIN_FRACTION = 0.1f;  // Space to reserve right of t=0.

  private QueryTracker tracker;
  private Paint dataPaint;   // Paint for the QPS curve itself.
  private Paint glowPaint;   // Paint for glow effect
  private Paint fillPaint;   // Paint for filled area
  private Paint pulsePaint;  // Paint for the radiating pulses that also serve as x-axis marks.
  private Paint particlePaint; // Paint for particle effects
  private Paint sparklePaint; // Paint for sparkle effects

  // Preallocate the curve to reduce garbage collection pressure.
  private int range = WINDOW_MS / RESOLUTION_MS;
  private float[] curve = new float[range];
  private float[] lines = new float[(curve.length - 1) * 4];

  // Particle system
  private Particle[] particles = new Particle[50];
  private Random random = new Random();
  
  // Sparkle system
  private Sparkle[] sparkles = new Sparkle[20];
  private long lastSparkleTime = 0;

  // Indicate whether the current curve is all zero, which allows an optimization.
  private boolean curveIsEmpty = true;

  // The peak value of the graph.  This value rises as needed, then falls to zero when the graph
  // is empty.
  private float max = 0;

  // Value of SystemClock.elapsedRealtime() for the current frame.
  private long now;

  // Animation states
  private float rippleProgress = 0;
  private float glowIntensity = 0;

  public HistoryGraph(Context context, AttributeSet attrs) {
    super(context, attrs);
    tracker = VpnController.getInstance().getTracker(context);

    int color = getResources().getColor(R.color.accent_good);

    // Initialize particles
    for (int i = 0; i < particles.length; i++) {
      particles[i] = new Particle();
    }
    
    // Initialize sparkles
    for (int i = 0; i < sparkles.length; i++) {
      sparkles[i] = new Sparkle();
    }

    // Main data paint with gradient
    dataPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dataPaint.setStrokeWidth(DATA_STROKE_WIDTH);
    dataPaint.setStyle(Paint.Style.STROKE);
    dataPaint.setStrokeCap(Paint.Cap.ROUND);
    dataPaint.setColor(color);

    // Glow effect paint
    glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    glowPaint.setStrokeWidth(DATA_STROKE_WIDTH + 8);
    glowPaint.setStyle(Paint.Style.STROKE);
    glowPaint.setStrokeCap(Paint.Cap.ROUND);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      glowPaint.setMaskFilter(new BlurMaskFilter(15, BlurMaskFilter.Blur.NORMAL));
    }

    // Fill area paint
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
    particlePaint.setColor(Color.argb(200, 255, 255, 255));

    // Sparkle paint
    sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    sparklePaint.setStyle(Paint.Style.FILL);
    sparklePaint.setColor(Color.WHITE);
    
    updatePaintsColor(color);
  }

  private void updatePaintsColor(int color) {
    dataPaint.setColor(color);
    glowPaint.setColor(color);
    pulsePaint.setColor(color);
    fillPaint.setColor(color);
    
    // Set glow with alpha
    glowPaint.setAlpha(100);
    
    updateDataShader(getWidth(), getHeight());
  }

  /**
   * @param color ARGB color to use for the lines in subsequent frames
   */
  public void setColor(int color) {
    updatePaintsColor(color);
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
    updateDataShader(w, h);
  }

  private void updateDataShader(int width, int height) {
    int color = dataPaint.getColor();
    int transparentColor = color & 0x00FFFFFF;
    
    // Create beautiful gradient for main line
    int[] gradientColors = {
        Color.argb(255, Color.red(color), Color.green(color), Color.blue(color)),
        Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)),
        Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)),
        Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
    };
    float[] positions = {0f, 0.3f, 0.7f, 1f};
    
    dataPaint.setShader(new LinearGradient(0, 0, width, 0, 
        gradientColors, positions, Shader.TileMode.CLAMP));
    
    // Fill gradient (vertical)
    int fillStart = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color));
    int fillEnd = Color.argb(10, Color.red(color), Color.green(color), Color.blue(color));
    fillPaint.setShader(new LinearGradient(0, 0, 0, height, fillStart, fillEnd, Shader.TileMode.CLAMP));
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

  // Particle class for special effects
  private class Particle {
    float x, y;
    float vx, vy;
    float life;
    float maxLife;
    boolean active = false;
    
    void activate(float startX, float startY, float velocity) {
      x = startX;
      y = startY;
      vx = (random.nextFloat() - 0.5f) * velocity;
      vy = (random.nextFloat() - 0.5f) * velocity;
      life = 0;
      maxLife = 0.5f + random.nextFloat() * 1.0f;
      active = true;
    }
    
    void update(float deltaTime) {
      if (!active) return;
      
      x += vx * deltaTime * 60;
      y += vy * deltaTime * 60;
      life += deltaTime;
      vy += 0.1f; // gravity
      
      if (life > maxLife) {
        active = false;
      }
    }
    
    void draw(Canvas canvas) {
      if (!active) return;
      
      float alpha = 1 - (life / maxLife);
      particlePaint.setAlpha((int)(alpha * 200));
      canvas.drawCircle(x, y, 2 * alpha, particlePaint);
    }
  }
  
  // Sparkle class for glitter effects
  private class Sparkle {
    float x, y;
    float size;
    float life;
    float maxLife;
    boolean active = false;
    
    void activate(float posX, float posY) {
      x = posX;
      y = posY;
      size = 2 + random.nextFloat() * 4;
      life = 0;
      maxLife = 0.3f + random.nextFloat() * 0.4f;
      active = true;
    }
    
    void update(float deltaTime) {
      if (!active) return;
      
      life += deltaTime;
      if (life > maxLife) {
        active = false;
      }
    }
    
    void draw(Canvas canvas) {
      if (!active) return;
      
      float progress = life / maxLife;
      float alpha = (progress < 0.5f) ? progress * 2 : 2 - progress * 2;
      sparklePaint.setAlpha((int)(alpha * 255));
      
      // Draw star-like sparkle
      canvas.drawCircle(x, y, size, sparklePaint);
      canvas.drawCircle(x, y, size * 0.6f, sparklePaint);
    }
  }

  private void updateParticles(float deltaTime, float currentX, float currentY, float intensity) {
    // Update existing particles
    for (Particle particle : particles) {
      particle.update(deltaTime);
    }
    
    // Spawn new particles based on intensity
    if (intensity > 0.1f) {
      int particlesToSpawn = (int)(intensity * 5);
      for (int i = 0; i < particlesToSpawn; i++) {
        for (Particle particle : particles) {
          if (!particle.active) {
            particle.activate(currentX, currentY, 2 + intensity * 3);
            break;
          }
        }
      }
    }
  }
  
  private void updateSparkles(float deltaTime, float currentX, float currentY, float intensity) {
    // Update existing sparkles
    for (Sparkle sparkle : sparkles) {
      sparkle.update(deltaTime);
    }
    
    // Spawn sparkles occasionally
    lastSparkleTime += (long)(deltaTime * 1000);
    if (lastSparkleTime > 200 && intensity > 0.2f) { // Every 200ms max
      lastSparkleTime = 0;
      int sparklesToSpawn = random.nextInt(3);
      for (int i = 0; i < sparklesToSpawn; i++) {
        for (Sparkle sparkle : sparkles) {
          if (!sparkle.active) {
            float offsetX = (random.nextFloat() - 0.5f) * 20;
            float offsetY = (random.nextFloat() - 0.5f) * 10;
            sparkle.activate(currentX + offsetX, currentY + offsetY);
            break;
          }
        }
      }
    }
  }

  private void drawFilledArea(Canvas canvas, float[] lines, float yoffset) {
    if (lines.length < 4) return;
    
    Path path = new Path();
    path.moveTo(lines[0], yoffset); // Start from bottom left
    
    // Follow the curve
    for (int i = 0; i < lines.length; i += 4) {
      path.lineTo(lines[i], lines[i+1]);
    }
    
    // Close the path to bottom right
    path.lineTo(lines[lines.length-2], yoffset);
    path.close();
    
    canvas.drawPath(path, fillPaint);
  }

  private void drawRippleEffect(Canvas canvas, float x, float y, float radius) {
    for (int i = 0; i < 3; i++) {
      float scale = 1 + i * 0.4f + rippleProgress * 0.5f;
      int alpha = 80 - i * 25 - (int)(rippleProgress * 40);
      if (alpha < 0) alpha = 0;
      
      pulsePaint.setAlpha(alpha);
      pulsePaint.setStrokeWidth(2 + i * 1.5f);
      canvas.drawCircle(x, y, radius * scale, pulsePaint);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    
    // Calculate delta time for smooth animations
    long currentTime = SystemClock.elapsedRealtime();
    static long lastFrameTime = currentTime;
    float deltaTime = (currentTime - lastFrameTime) / 1000.0f;
    lastFrameTime = currentTime;
    
    // Update animation states
    rippleProgress += deltaTime * 2;
    if (rippleProgress > 1) rippleProgress = 0;
    
    // Update glow intensity based on recent activity
    float targetGlow = curveIsEmpty ? 0 : Math.min(1, curve[curve.length-1] / Math.max(1, max));
    glowIntensity += (targetGlow - glowIntensity) * deltaTime * 5;

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
    float currentIntensity = 0;
    
    if (updateCurve()) {
      updateMax();

      float xscale = usableWidth / (curve.length - 1);
      float yscale = max == 0 ? 0 : usableHeight / max;

      // Convert the curve into lines in the appropriate scale
      for (int i = 1; i < curve.length; ++i) {
        int j = (i - 1) * 4;
        lines[j] = (i - 1) * xscale + xoffset;
        lines[j + 1] = curve[i - 1] * yscale + yoffset;
        lines[j + 2] = i * xscale + xoffset;
        lines[j + 3] = curve[i] * yscale + yoffset;
      }
      
      currentIntensity = curve[curve.length-1] / Math.max(1, max);
      
      // Draw filled area first
      drawFilledArea(canvas, lines, yoffset);
      
      // Draw glow effect
      glowPaint.setAlpha((int)(glowIntensity * 100));
      canvas.drawLines(lines, glowPaint);
      
      // Draw main line
      canvas.drawLines(lines, dataPaint);
      
      rightEndY = lines[1];
    } else {
      max = 0;
      // Draw a horizontal line at y = 0
      canvas.drawLine(xoffset, yoffset, xoffset + usableWidth, yoffset, dataPaint);
      rightEndY = yoffset;
      currentIntensity = 0;
    }

    // Update special effects
    float currentX = xoffset;
    updateParticles(deltaTime, currentX, rightEndY, currentIntensity);
    updateSparkles(deltaTime, currentX, rightEndY, currentIntensity);

    // Draw particles
    for (Particle particle : particles) {
      particle.draw(canvas);
    }
    
    // Draw sparkles
    for (Sparkle sparkle : sparkles) {
      sparkle.draw(canvas);
    }

    // Draw ripple effect at the right end
    float tagRadius = 3 * DATA_STROKE_WIDTH * (1 + glowIntensity * 0.5f);
    float tagX = xoffset - tagRadius;
    
    drawRippleEffect(canvas, tagX, rightEndY, tagRadius);
    
    // Draw main endpoint with glow
    dataPaint.setAlpha(255);
    canvas.drawCircle(tagX, rightEndY, tagRadius, dataPaint);
    
    // Draw inner bright point
    sparklePaint.setAlpha(255);
    canvas.drawCircle(tagX, rightEndY, tagRadius * 0.3f, sparklePaint);

    // Draw pulses at regular intervals
    float maxRadius = getWidth() - tagX;
    for (long age = now % PULSE_INTERVAL_MS; age < WINDOW_MS; age += PULSE_INTERVAL_MS) {
      float fraction = ((float) age) / WINDOW_MS;
      float radius = tagRadius + fraction * (maxRadius - tagRadius);
      int alpha = (int) (150 * (1 - fraction));
      pulsePaint.setAlpha(alpha);
      pulsePaint.setStrokeWidth(1 + fraction * 3);
      canvas.drawCircle(tagX, yoffset, radius, pulsePaint);
    }

    // Draw the next frame at the UI's preferred update frequency.
    postInvalidateOnAnimation();
  }
  }
