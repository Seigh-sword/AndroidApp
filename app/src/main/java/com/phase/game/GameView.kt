package com.phase.game

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.*
import kotlin.random.Random

/**
 * GameView — the entire game lives here.
 *
 * Concept: PHASE
 *   Two parallel dimensions coexist. The player is a being able to "phase"
 *   between the LIGHT and SHADOW realms. Each realm has its own platform
 *   arrangement and its own enemy population. To survive you must constantly
 *   switch phases to dodge enemies, collect essence, and reach higher levels.
 *
 *   The world slowly rotates and pulses to a procedural "rhythm". The faster
 *   you survive, the more intense it becomes.
 *
 * Controls:
 *   - Drag finger: move player (direct positional control).
 *   - Quick tap (no drag): phase shift between LIGHT and SHADOW.
 *   - The world also forces a phase shift on every Nth beat, so the player
 *     must adapt to a constantly shifting rhythm.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // -------- Persistence --------
    private val prefs: SharedPreferences =
        context.getSharedPreferences("phase_prefs", Context.MODE_PRIVATE)
    private var bestScore: Int = prefs.getInt("best_score", 0)
    private var muted: Boolean = prefs.getBoolean("muted", false)

    // -------- Game state --------
    private enum class Phase { LIGHT, SHADOW }
    private enum class State { MENU, PLAYING, GAME_OVER }

    private var state: State = State.MENU
    private var phase: Phase = Phase.LIGHT
    private var phaseEnergy: Float = 1.0f         // 0..1; drains when shifting, refills slowly
    private var score: Int = 0
    private var level: Int = 1
    private var combo: Int = 0
    private var comboTimer: Float = 0f

    // -------- World --------
    private var worldTime: Float = 0f
    private var phaseShiftTimer: Float = 0f        // forces rhythm shifts
    private var beatPulse: Float = 0f              // 0..1 decays each beat
    private var beatInterval: Float = 1.2f         // seconds per beat (shrinks with level)
    private var lastBeatTime: Float = 0f
    private var screenShake: Float = 0f
    private var flash: Float = 0f                  // 0..1 white flash on phase shift

    // -------- Player --------
    private data class Player(
        var x: Float, var y: Float,
        var vx: Float = 0f, var vy: Float = 0f,
        var r: Float = 26f,
        var trail: MutableList<PointF> = mutableListOf(),
        var invuln: Float = 0f
    )
    private val player = Player(0f, 0f)

    // -------- Entities --------
    private data class Platform(
        val isLight: Boolean,        // belongs to which dimension
        val x: Float, val y: Float,
        val w: Float, val h: Float,
        val moving: Boolean,
        var phaseOff: Float
    )
    private data class Enemy(
        val isLight: Boolean,
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var r: Float,
        var life: Float
    )
    private data class Orb(
        val isLight: Boolean,
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var r: Float,
        var age: Float = 0f
    )
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float, var maxLife: Float,
        val color: Int, val size: Float
    )

    private val platforms = mutableListOf<Platform>()
    private val enemies = mutableListOf<Enemy>()
    private val orbs = mutableListOf<Orb>()
    private val particles = mutableListOf<Particle>()

    // -------- Input --------
    private var dragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime: Long = 0L
    private var touchMoved = false
    private var tapFlash: Float = 0f
    private val tapTimeoutMs = 220L
    private val tapSlopPx = 18f

    // -------- Rendering --------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintNoAA = Paint()
    private val tmpPath = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // -------- Background stars --------
    private data class Star(var x: Float, var y: Float, var r: Float, var twinkle: Float, var speed: Float)
    private val stars = mutableListOf<Star>()

    // -------- Sizing --------
    private var W = 0f
    private var H = 0f
    private var groundY = 0f
    private var gravity = 0f
    private var initialized = false

    // -------- Colors --------
    private val colBg1 = ResourcesCompat.getColor(resources, R.color.void_black, null)
    private val colBg2 = ResourcesCompat.getColor(resources, R.color.void_deep, null)
    private val colLightA = ResourcesCompat.getColor(resources, R.color.phase_light_a, null)
    private val colLightB = ResourcesCompat.getColor(resources, R.color.phase_light_b, null)
    private val colDarkA = ResourcesCompat.getColor(resources, R.color.phase_dark_a, null)
    private val colDarkB = ResourcesCompat.getColor(resources, R.color.phase_dark_b, null)
    private val colWhite = ResourcesCompat.getColor(resources, R.color.white, null)

    // -------- Loop --------
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameNs: Long = 0L
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNs = now
            update(dt)
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    // -------- Vibrator (haptics) --------
    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        setBackgroundColor(colBg1)
        isFocusable = true
        isClickable = true
    }

    fun start() {
        if (!initialized) return
        handler.removeCallbacks(tick)
        lastFrameNs = 0L
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()
        groundY = H - 80f * resources.displayMetrics.density / 3f
        gravity = H * 1.2f
        if (!initialized) {
            seedStars()
            resetWorld()
            initialized = true
        } else {
            // re-seed player y to ground
            player.x = W / 2f
            player.y = groundY - player.r - 10f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    // -------- World setup --------
    private fun seedStars() {
        stars.clear()
        val rng = Random(42)
        repeat(120) {
            stars.add(
                Star(
                    x = rng.nextFloat() * W,
                    y = rng.nextFloat() * H,
                    r = rng.nextFloat() * 1.6f + 0.3f,
                    twinkle = rng.nextFloat() * 6.28f,
                    speed = rng.nextFloat() * 0.4f + 0.1f
                )
            )
        }
    }

    private fun resetWorld() {
        platforms.clear()
        enemies.clear()
        orbs.clear()
        particles.clear()
        player.x = W / 2f
        player.y = groundY - player.r - 10f
        player.vx = 0f
        player.vy = 0f
        player.trail.clear()
        player.invuln = 0f
        phase = Phase.LIGHT
        phaseEnergy = 1f
        phaseShiftTimer = 0f
        score = 0
        level = 1
        combo = 0
        comboTimer = 0f
        beatInterval = 1.2f
        lastBeatTime = 0f
        screenShake = 0f
        flash = 0f
        spawnLevel()
    }

    private fun spawnLevel() {
        platforms.clear()
        enemies.clear()
        orbs.clear()

        val rng = Random(System.nanoTime() + level * 31L)
        val count = (5 + level).coerceAtMost(11)
        // Always at least one platform of each phase near the player
        val phaseOf: (Int) -> Boolean = { idx -> if (level == 1) idx % 2 == 0 else rng.nextBoolean() }
        for (i in 0 until count) {
            val light = phaseOf(i)
            val y = if (i == 0) groundY - 20f
                else (H * 0.18f + rng.nextFloat() * (H * 0.62f))
            val x = rng.nextFloat() * (W - 160f) + 80f
            val w = 110f + rng.nextFloat() * 90f
            val h = 16f + rng.nextFloat() * 10f
            val moving = rng.nextFloat() < 0.30f && i > 0 && level >= 2
            platforms.add(
                Platform(
                    isLight = light,
                    x = x, y = y, w = w, h = h,
                    moving = moving,
                    phaseOff = rng.nextFloat() * 6.28f
                )
            )
        }

        // Spawn enemies — bias to current phase, but always a few in the
        // opposite phase so the player has to think about which side to be on.
        val enemyCount = (1 + level).coerceAtMost(8)
        repeat(enemyCount) {
            val target = platforms.random(rng)
            // Level 1: 100% in opposite phase (safe at start). Later: more mixed.
            val opposite = if (level == 1) true
                else rng.nextFloat() < 0.55f
            val light = if (opposite) phase != Phase.LIGHT else phase == Phase.LIGHT
            val er = 18f + rng.nextFloat() * 12f
            val speed = 60f + level * 12f + rng.nextFloat() * 60f
            val dir = if (rng.nextBoolean()) 1f else -1f
            enemies.add(
                Enemy(
                    isLight = light,
                    x = target.x + target.w / 2f + dir * (target.w / 2f + er + 4f),
                    y = target.y - er - 2f,
                    vx = dir * speed,
                    vy = 0f,
                    r = er,
                    life = 1f
                )
            )
        }

        // Spawn orbs
        val orbCount = 2 + (level / 2)
        repeat(orbCount) {
            val target = platforms.random(rng)
            val light = if (rng.nextFloat() < 0.5f) phase == Phase.LIGHT else phase == Phase.SHADOW
            val r = 10f + rng.nextFloat() * 6f
            orbs.add(
                Orb(
                    isLight = light,
                    x = target.x + target.w / 2f,
                    y = target.y - 60f - rng.nextFloat() * 80f,
                    vx = (rng.nextFloat() - 0.5f) * 30f,
                    vy = 0f,
                    r = r
                )
            )
        }
    }

    // -------- Input handling --------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (state == State.MENU) {
                    startGame()
                    return true
                }
                if (state == State.GAME_OVER) {
                    resetWorld()
                    state = State.PLAYING
                    return true
                }
                // Playing
                dragging = true
                activePointerId = event.getPointerId(idx)
                touchStartX = x
                touchStartY = y
                touchStartTime = System.currentTimeMillis()
                touchMoved = false
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (state != State.PLAYING) return true
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (abs(x - touchStartX) > tapSlopPx || abs(y - touchStartY) > tapSlopPx) {
                    touchMoved = true
                }
                // Direct positional control feels best on a phone
                val targetX = x.coerceIn(player.r, W - player.r)
                val targetY = y.coerceIn(player.r, groundY - player.r)
                val desiredVx = (targetX - player.x) * 16f
                val desiredVy = (targetY - player.y) * 16f
                val maxV = H * 1.4f
                player.vx = desiredVx.coerceIn(-maxV, maxV)
                player.vy = desiredVy.coerceIn(-maxV, maxV)
                lastTouchX = x
                lastTouchY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.findPointerIndex(
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex
                    else 0
                )
                val duration = System.currentTimeMillis() - touchStartTime
                if (state == State.PLAYING && idx >= 0) {
                    val upX = event.getX(idx)
                    val upY = event.getY(idx)
                    val moved = abs(upX - touchStartX) > tapSlopPx || abs(upY - touchStartY) > tapSlopPx
                    // A "tap" = quick press without dragging. Triggers phase shift.
                    if (!moved && duration < tapTimeoutMs) {
                        doPhaseShift()
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    dragging = false
                    activePointerId = -1
                }
            }
        }
        return true
    }

    private fun doPhaseShift() {
        if (phaseEnergy < 0.15f) {
            // out of energy, weak pulse
            flash = 0.25f
            screenShake = max(screenShake, 4f)
            tapFlash = 0.6f
            return
        }
        phase = if (phase == Phase.LIGHT) Phase.SHADOW else Phase.LIGHT
        phaseEnergy -= 0.2f
        flash = 0.85f
        tapFlash = 1f
        screenShake = max(screenShake, 14f)
        lightHaptic(18)
        spawnPhaseBurst()
    }

    private fun spawnPhaseBurst() {
        val color = if (phase == Phase.LIGHT) colLightA else colDarkA
        repeat(40) {
            val a = Random.nextFloat() * 6.283f
            val sp = 80f + Random.nextFloat() * 220f
            particles.add(
                Particle(
                    x = player.x, y = player.y,
                    vx = cos(a) * sp, vy = sin(a) * sp,
                    life = 0.6f + Random.nextFloat() * 0.4f,
                    maxLife = 0.6f + Random.nextFloat() * 0.4f,
                    color = color,
                    size = 2f + Random.nextFloat() * 3f
                )
            )
        }
    }

    private fun spawnTrail(n: Int, color: Int) {
        repeat(n) {
            particles.add(
                Particle(
                    x = player.x + (Random.nextFloat() - 0.5f) * 8f,
                    y = player.y + (Random.nextFloat() - 0.5f) * 8f,
                    vx = -player.vx * 0.05f + (Random.nextFloat() - 0.5f) * 40f,
                    vy = -player.vy * 0.05f + (Random.nextFloat() - 0.5f) * 40f,
                    life = 0.3f + Random.nextFloat() * 0.3f,
                    maxLife = 0.6f,
                    color = color,
                    size = 2f + Random.nextFloat() * 2.5f
                )
            )
        }
    }

    private fun lightHaptic(ms: Long) {
        if (muted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Throwable) { }
    }

    // -------- Update --------
    private fun update(dt: Float) {
        worldTime += dt
        flash = (flash - dt * 2.6f).coerceAtLeast(0f)
        tapFlash = (tapFlash - dt * 2.0f).coerceAtLeast(0f)
        screenShake = (screenShake - dt * 30f).coerceAtLeast(0f)
        player.invuln = (player.invuln - dt).coerceAtLeast(0f)

        // Refill phase energy slowly, faster on level up
        phaseEnergy = (phaseEnergy + dt * 0.05f).coerceAtMost(1f)

        // Stars parallax
        for (s in stars) {
            s.twinkle += dt * s.speed * 6f
            s.y += dt * (8f + s.speed * 16f)
            if (s.y > H) {
                s.y = -2f
                s.x = Random.nextFloat() * W
            }
        }

        // Beat / rhythm
        lastBeatTime += dt
        if (lastBeatTime >= beatInterval) {
            lastBeatTime = 0f
            beatPulse = 1f
            // The world forces a phase shift on every Nth beat
            phaseShiftTimer -= beatInterval
            if (phaseShiftTimer <= 0f) {
                // Auto-shift if player has enough energy. This is a forced
                // dimensional transition that the player must adapt to.
                if (phaseEnergy >= 0.15f) {
                    doPhaseShift()
                    // Brief invulnerability so the shift isn't instantly lethal
                    player.invuln = max(player.invuln, 0.25f)
                }
                val forcedBeats = max(1, 4 - level / 3)
                phaseShiftTimer = beatInterval * forcedBeats
            }
        }
        beatPulse = (beatPulse - dt * 2.2f).coerceAtLeast(0f)

        if (state != State.PLAYING) return

        // Difficulty scales
        val targetBeat = (1.2f - (level - 1) * 0.06f).coerceAtLeast(0.45f)
        beatInterval += (targetBeat - beatInterval) * dt * 0.5f
        score += (10 * dt * (1f + level * 0.15f)).toInt()

        // Combo timer
        comboTimer -= dt
        if (comboTimer <= 0f) combo = 0

        // Player physics
        if (!dragging) {
            player.vx *= 0.92f
            player.vy *= 0.92f
        }
        player.vy += gravity * dt
        player.x += player.vx * dt
        player.y += player.vy * dt

        // Bounds
        if (player.x < player.r) { player.x = player.r; player.vx = abs(player.vx) * 0.4f }
        if (player.x > W - player.r) { player.x = W - player.r; player.vx = -abs(player.vx) * 0.4f }
        if (player.y > groundY - player.r) {
            player.y = groundY - player.r
            player.vy = -H * 0.55f
            spawnTrail(6, if (phase == Phase.LIGHT) colLightB else colDarkB)
        }
        if (player.y < player.r) { player.y = player.r; player.vy = abs(player.vy) * 0.4f }

        // Platform collisions (only in current phase)
        var onPlatform = false
        for (p in platforms) {
            if (p.isLight != (phase == Phase.LIGHT)) continue
            val px = p.x
            val py = p.y
            val pw = p.w
            val ph = p.h
            // axis-aligned; player is circle
            val closestX = player.x.coerceIn(px, px + pw)
            val closestY = player.y.coerceIn(py, py + ph)
            val ddx = player.x - closestX
            val ddy = player.y - closestY
            val dist2 = ddx * ddx + ddy * ddy
            if (dist2 < player.r * player.r) {
                // Resolve along the smaller axis
                if (abs(ddx) > abs(ddy)) {
                    if (ddx > 0) player.x = px + pw + player.r else player.x = px - player.r
                    player.vx = -player.vx * 0.3f
                } else {
                    if (ddy > 0) {
                        player.y = py + ph + player.r
                        player.vy = abs(player.vy) * 0.3f
                    } else {
                        player.y = py - player.r
                        if (player.vy > 0f) player.vy = 0f
                        onPlatform = true
                    }
                }
            } else {
                // Top landing tolerance
                if (player.vy >= 0f &&
                    player.x in (px - player.r)..(px + pw + player.r) &&
                    player.y in (py - player.r - 4f)..(py - player.r + 6f)
                ) {
                    player.y = py - player.r
                    player.vy = 0f
                    onPlatform = true
                }
            }
        }
        if (onPlatform && abs(player.vx) < 8f) {
            // gentle friction
            player.vx *= 0.85f
        }

        // Moving platforms
        for (p in platforms) {
            if (!p.moving) continue
            val t = worldTime + p.phaseOff
            p.x = ((W - p.w) / 2f) + sin(t) * ((W - p.w) / 2f - 20f) * 0.8f
        }

        // Enemies
        val it = enemies.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.x += e.vx * dt
            // Patrol on top of their home platform — bounce off edges
            if (e.x < e.r) { e.x = e.r; e.vx = abs(e.vx) }
            if (e.x > W - e.r) { e.x = W - e.r; e.vx = -abs(e.vx) }
            e.life -= dt
            if (e.life <= 0f) it.remove()
        }

        // Orbs
        for (o in orbs) {
            o.age += dt
            o.x += o.vx * dt
            o.y += sin(o.age * 3f + o.x * 0.01f) * 0.4f
            if (o.x < o.r) { o.x = o.r; o.vx = abs(o.vx) }
            if (o.x > W - o.r) { o.x = W - o.r; o.vx = -abs(o.vx) }
        }

        // Collisions: enemies (only in their phase)
        for (e in enemies) {
            if (e.isLight != (phase == Phase.LIGHT)) continue
            val ddx = e.x - player.x
            val ddy = e.y - player.y
            val sumR = e.r + player.r
            if (ddx * ddx + ddy * ddy < sumR * sumR) {
                if (player.invuln <= 0f) {
                    gameOver()
                    return
                }
            }
        }

        // Orb collection (only in their phase)
        val oit = orbs.iterator()
        while (oit.hasNext()) {
            val o = oit.next()
            if (o.isLight != (phase == Phase.LIGHT)) continue
            val ddx = o.x - player.x
            val ddy = o.y - player.y
            val sumR = o.r + player.r
            if (ddx * ddx + ddy * ddy < sumR * sumR) {
                score += 50 + combo * 5
                combo += 1
                comboTimer = 2.5f
                phaseEnergy = (phaseEnergy + 0.15f).coerceAtMost(1f)
                spawnOrbBurst(o.x, o.y, if (phase == Phase.LIGHT) colLightA else colDarkA)
                lightHaptic(10)
                oit.remove()
            }
        }

        // Particles
        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.life -= dt
            p.vx *= 0.96f
            p.vy *= 0.96f
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.life <= 0f) pit.remove()
        }

        // Trail for player
        player.trail.add(PointF(player.x, player.y))
        if (player.trail.size > 18) player.trail.removeAt(0)

        // Level progression
        val target = 150 + (level - 1) * 120
        if (score >= target) {
            level += 1
            spawnLevel()
            flash = 0.6f
            screenShake = max(screenShake, 10f)
            lightHaptic(30)
            // Refill energy on level up
            phaseEnergy = 1f
            // Re-place the player safely
            player.x = W / 2f
            player.y = groundY - player.r - 10f
            player.vx = 0f
            player.vy = 0f
            player.invuln = 1.0f
        }
    }

    private fun spawnOrbBurst(x: Float, y: Float, color: Int) {
        repeat(18) {
            val a = Random.nextFloat() * 6.28f
            val sp = 60f + Random.nextFloat() * 140f
            particles.add(
                Particle(
                    x = x, y = y,
                    vx = cos(a) * sp, vy = sin(a) * sp,
                    life = 0.4f + Random.nextFloat() * 0.3f,
                    maxLife = 0.7f,
                    color = color,
                    size = 2f + Random.nextFloat() * 2f
                )
            )
        }
    }

    private fun startGame() {
        resetWorld()
        state = State.PLAYING
    }

    private fun gameOver() {
        state = State.GAME_OVER
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt("best_score", bestScore).apply()
        }
        flash = 1f
        screenShake = 26f
        lightHaptic(60)
        spawnExplosion(player.x, player.y)
    }

    private fun spawnExplosion(x: Float, y: Float) {
        repeat(80) {
            val a = Random.nextFloat() * 6.28f
            val sp = 80f + Random.nextFloat() * 360f
            val c = if (Random.nextBoolean()) colLightA else colDarkA
            particles.add(
                Particle(
                    x = x, y = y,
                    vx = cos(a) * sp, vy = sin(a) * sp,
                    life = 0.7f + Random.nextFloat() * 0.6f,
                    maxLife = 1.3f,
                    color = c,
                    size = 2f + Random.nextFloat() * 4f
                )
            )
        }
    }

    // -------- Render --------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (W == 0f || H == 0f) return

        val shakeX = if (screenShake > 0f) (Random.nextFloat() - 0.5f) * screenShake else 0f
        val shakeY = if (screenShake > 0f) (Random.nextFloat() - 0.5f) * screenShake else 0f
        val save = canvas.save()
        canvas.translate(shakeX, shakeY)

        drawBackground(canvas)
        drawPlatforms(canvas)
        drawOrbs(canvas)
        drawEnemies(canvas)
        drawPlayer(canvas)
        drawParticles(canvas)
        drawPhaseEnergyBar(canvas)
        drawHUD(canvas)
        drawCenterOverlay(canvas)

        if (tapFlash > 0f) {
            // Pulse ring at the player's position when a tap happened
            val r = (1f - tapFlash) * H * 0.4f + player.r * 1.5f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = if (phase == Phase.LIGHT) colLightA else colDarkA
            paint.alpha = (tapFlash * 200).toInt().coerceIn(0, 255)
            canvas.drawCircle(player.x, player.y, r, paint)
            paint.style = Paint.Style.FILL
        }

        canvas.restoreToCount(save)

        if (flash > 0f) {
            paint.color = (clamp(flash, 0f, 1f) * 255).toInt().shl(24) or 0x00FFFFFF
            canvas.drawRect(0f, 0f, W, H, paint)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        // Vertical gradient based on current phase
        val top = if (phase == Phase.LIGHT) colLightB else colDarkB
        val bot = colBg1
        val grad = LinearGradient(
            0f, 0f, 0f, H,
            blend(top, colBg2, 0.55f), bot,
            Shader.TileMode.CLAMP
        )
        paintNoAA.shader = grad
        canvas.drawRect(0f, 0f, W, H, paintNoAA)
        paintNoAA.shader = null

        // Stars — only visible in their matching phase
        for (s in stars) {
            val inLight = ((sin(s.twinkle) + 1f) * 0.5f)
            val baseA = if (phase == Phase.LIGHT) inLight else 1f - inLight
            val a = (clamp(baseA, 0f, 1f) * (180 + 75 * sin(worldTime * 1.2f + s.twinkle))).toInt().coerceIn(0, 255)
            starPaint.color = (a shl 24) or 0x00FFFFFF
            canvas.drawCircle(s.x, s.y, s.r + beatPulse * 0.6f, starPaint)
        }

        // Distant dimensional ribbons
        paintNoAA.color = (if (phase == Phase.LIGHT) colLightA else colDarkA)
        paintNoAA.alpha = 28
        val ribbonCount = 8
        for (i in 0 until ribbonCount) {
            val baseY = H * (0.15f + i * 0.08f)
            val amp = 30f + i * 4f
            val path = Path()
            path.moveTo(0f, baseY)
            var x = 0f
            while (x <= W) {
                val y = baseY + sin((x + worldTime * 80f + i * 40f) * 0.012f) * amp
                path.lineTo(x, y)
                x += 16f
            }
            paintNoAA.strokeWidth = 1.5f + i * 0.4f
            paintNoAA.style = Paint.Style.STROKE
            canvas.drawPath(path, paintNoAA)
        }
        paintNoAA.style = Paint.Style.FILL
        paintNoAA.alpha = 255
    }

    private fun drawPlatforms(canvas: Canvas) {
        for (p in platforms) {
            val isCurrent = p.isLight == (phase == Phase.LIGHT)
            val baseColor = if (p.isLight) colLightA else colDarkA
            val accent = if (p.isLight) colLightB else colDarkB

            // The "ghost" platform in the other phase is drawn faintly
            if (!isCurrent) {
                paint.color = baseColor
                paint.alpha = 36
                canvas.drawRoundRect(p.x, p.y, p.x + p.w, p.y + p.h, 10f, 10f, paint)
            } else {
                // glow under the platform
                glowPaint.color = baseColor
                glowPaint.alpha = 110
                canvas.drawRoundRect(p.x - 4f, p.y - 2f, p.x + p.w + 4f, p.y + p.h + 8f, 14f, 14f, glowPaint)
                paint.color = baseColor
                paint.alpha = 230
                canvas.drawRoundRect(p.x, p.y, p.x + p.w, p.y + p.h, 10f, 10f, paint)
                // accent stripe
                paint.color = accent
                paint.alpha = 220
                canvas.drawRoundRect(p.x, p.y, p.x + p.w, p.y + 4f, 4f, 4f, paint)
                // moving indicator
                if (p.moving) {
                    paint.color = colWhite
                    paint.alpha = 130
                    val cx = p.x + p.w / 2f
                    canvas.drawCircle(cx, p.y + p.h / 2f, 2f, paint)
                }
            }
        }

        // Ground
        paint.color = if (phase == Phase.LIGHT) colLightA else colDarkA
        paint.alpha = 100
        canvas.drawRect(0f, groundY, W, H, paint)
        paint.color = colWhite
        paint.alpha = 60
        canvas.drawRect(0f, groundY, W, groundY + 2f, paint)
    }

    private fun drawOrbs(canvas: Canvas) {
        for (o in orbs) {
            val isCurrent = o.isLight == (phase == Phase.LIGHT)
            val color = if (o.isLight) colLightA else colDarkA
            if (!isCurrent) {
                paint.color = color
                paint.alpha = 50
                canvas.drawCircle(o.x, o.y, o.r * 0.9f, paint)
            } else {
                glowPaint.color = color
                glowPaint.alpha = 170
                canvas.drawCircle(o.x, o.y, o.r * 2.4f, glowPaint)
                paint.color = color
                paint.alpha = 230
                canvas.drawCircle(o.x, o.y, o.r, paint)
                paint.color = colWhite
                paint.alpha = 220
                canvas.drawCircle(o.x - o.r * 0.3f, o.y - o.r * 0.3f, o.r * 0.35f, paint)
            }
        }
    }

    private fun drawEnemies(canvas: Canvas) {
        for (e in enemies) {
            val isCurrent = e.isLight == (phase == Phase.LIGHT)
            val color = if (e.isLight) colDarkA else colLightA
            if (!isCurrent) {
                paint.color = color
                paint.alpha = 36
                canvas.drawCircle(e.x, e.y, e.r, paint)
            } else {
                // Menacing glow
                glowPaint.color = color
                glowPaint.alpha = 130
                canvas.drawCircle(e.x, e.y, e.r * 1.8f, glowPaint)

                paint.color = color
                paint.alpha = 235
                // Diamond shape for enemy
                tmpPath.reset()
                tmpPath.moveTo(e.x, e.y - e.r)
                tmpPath.lineTo(e.x + e.r, e.y)
                tmpPath.lineTo(e.x, e.y + e.r)
                tmpPath.lineTo(e.x - e.r, e.y)
                tmpPath.close()
                canvas.drawPath(tmpPath, paint)
                // Eye
                paint.color = colWhite
                paint.alpha = 240
                canvas.drawCircle(e.x + (if (e.vx > 0) 3f else -3f), e.y - 2f, e.r * 0.18f, paint)
            }
        }
    }

    private fun drawPlayer(canvas: Canvas) {
        if (state == State.MENU) {
            // floating idle
            val cy = H * 0.45f + sin(worldTime * 1.6f) * 18f
            drawPlayerAt(player.x, cy, alpha = 200)
            return
        }
        // trail
        for (i in player.trail.indices) {
            val p = player.trail[i]
            val t = i / player.trail.size.toFloat()
            val c = if (phase == Phase.LIGHT) colLightA else colDarkA
            paint.color = c
            paint.alpha = (60 * t).toInt()
            canvas.drawCircle(p.x, p.y, player.r * (0.35f + t * 0.5f), paint)
        }
        if (player.invuln > 0f && (player.invuln * 20f).toInt() % 2 == 0) return
        drawPlayerAt(player.x, player.y, alpha = 255)
    }

    private fun drawPlayerAt(x: Float, y: Float, alpha: Int) {
        val color = if (phase == Phase.LIGHT) colLightA else colDarkA
        val accent = if (phase == Phase.LIGHT) colLightB else colDarkB

        // Outer aura
        glowPaint.color = color
        glowPaint.alpha = 160
        canvas.drawCircle(x, y, player.r * 1.8f, glowPaint)

        // Outer ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = color
        paint.alpha = alpha
        canvas.drawCircle(x, y, player.r, paint)

        // Inner core (split by phase)
        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = alpha
        canvas.drawArc(
            x - player.r * 0.7f, y - player.r * 0.7f,
            x + player.r * 0.7f, y + player.r * 0.7f,
            if (phase == Phase.LIGHT) 180f else 0f,
            180f, true, paint
        )
        paint.color = colWhite
        paint.alpha = (alpha * 0.95f).toInt()
        canvas.drawCircle(x, y, player.r * 0.18f, paint)
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val a = (clamp(p.life / p.maxLife, 0f, 1f) * 255).toInt()
            paint.color = p.color
            paint.alpha = a
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
    }

    private fun drawPhaseEnergyBar(canvas: Canvas) {
        val pad = 24f * resources.displayMetrics.density / 2.5f
        val barW = W * 0.32f
        val barH = 8f
        val x = (W - barW) / 2f
        val y = pad
        paint.color = colWhite
        paint.alpha = 60
        canvas.drawRoundRect(x - 2f, y - 2f, x + barW + 2f, y + barH + 2f, 6f, 6f, paint)
        paint.color = if (phase == Phase.LIGHT) colLightA else colDarkA
        paint.alpha = 220
        canvas.drawRoundRect(x, y, x + barW * phaseEnergy, y + barH, 6f, 6f, paint)
        // phase label
        paint.color = if (phase == Phase.LIGHT) colLightA else colDarkA
        paint.alpha = 220
        paint.textSize = 18f * resources.displayMetrics.scaledDensity
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val label = if (phase == Phase.LIGHT) "LIGHT" else "SHADOW"
        val tw = paint.measureText(label)
        canvas.drawText(label, (W - tw) / 2f, y - 8f, paint)
    }

    private fun drawHUD(canvas: Canvas) {
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.color = colWhite
        paint.alpha = 220
        val density = resources.displayMetrics.scaledDensity
        paint.textSize = 16f * density
        val scoreText = "SCORE $score"
        val bestText = "BEST $bestScore"
        paint.alpha = 180
        canvas.drawText(scoreText, 18f * density, 28f * density, paint)
        val bw = paint.measureText(bestText)
        canvas.drawText(bestText, W - bw - 18f * density, 28f * density, paint)

        paint.textSize = 12f * density
        paint.alpha = 160
        canvas.drawText("LVL $level  COMBO x$combo", 18f * density, 46f * density, paint)
    }

    private fun drawCenterOverlay(canvas: Canvas) {
        when (state) {
            State.MENU -> {
                val titleColor = if (phase == Phase.LIGHT) colLightA else colDarkA
                paint.color = titleColor
                paint.alpha = 230
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 96f * resources.displayMetrics.scaledDensity
                val title = "PHASE"
                val tw = paint.measureText(title)
                canvas.drawText(title, (W - tw) / 2f, H * 0.30f, paint)

                paint.color = colWhite
                paint.alpha = 180
                paint.textSize = 14f * resources.displayMetrics.scaledDensity
                val sub = "Two dimensions. One survival."
                val sw = paint.measureText(sub)
                canvas.drawText(sub, (W - sw) / 2f, H * 0.30f + 30f * resources.displayMetrics.scaledDensity, paint)

                // Controls
                paint.alpha = 200
                paint.textSize = 15f * resources.displayMetrics.scaledDensity
                val c1 = "DRAG  —  Move"
                val c2 = "TAP  —  Phase Shift"
                val cw1 = paint.measureText(c1)
                val cw2 = paint.measureText(c2)
                canvas.drawText(c1, (W - cw1) / 2f, H * 0.50f, paint)
                canvas.drawText(c2, (W - cw2) / 2f, H * 0.50f + 22f * resources.displayMetrics.scaledDensity, paint)

                paint.alpha = (150 + 80 * sin(worldTime * 4f)).toInt().coerceIn(0, 255)
                paint.textSize = 22f * resources.displayMetrics.scaledDensity
                val tap = context.getString(R.string.tap_to_start)
                val tw2 = paint.measureText(tap)
                canvas.drawText(tap, (W - tw2) / 2f, H * 0.66f, paint)

                if (bestScore > 0) {
                    paint.alpha = 200
                    paint.textSize = 16f * resources.displayMetrics.scaledDensity
                    val best = "BEST  $bestScore"
                    val bw = paint.measureText(best)
                    canvas.drawText(best, (W - bw) / 2f, H * 0.74f, paint)
                }
            }
            State.GAME_OVER -> {
                paint.color = colDarkA
                paint.alpha = 200
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = 48f * resources.displayMetrics.scaledDensity
                val go = context.getString(R.string.game_over)
                val gw = paint.measureText(go)
                canvas.drawText(go, (W - gw) / 2f, H * 0.38f, paint)

                paint.color = colWhite
                paint.alpha = 230
                paint.textSize = 22f * resources.displayMetrics.scaledDensity
                val s = "SCORE $score   BEST $bestScore"
                val sw = paint.measureText(s)
                canvas.drawText(s, (W - sw) / 2f, H * 0.46f, paint)

                paint.alpha = (150 + 80 * sin(worldTime * 4f)).toInt().coerceIn(0, 255)
                val retry = context.getString(R.string.tap_again)
                val rw = paint.measureText(retry)
                canvas.drawText(retry, (W - rw) / 2f, H * 0.54f, paint)
            }
            else -> { /* no overlay during play */ }
        }
    }

    // -------- Helpers --------
    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    private fun blend(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun toggleMute(): Boolean {
        muted = !muted
        prefs.edit().putBoolean("muted", muted).apply()
        return muted
    }

    val isMuted: Boolean get() = muted
    val currentBest: Int get() = bestScore
}
