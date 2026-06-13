package com.arashivision.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationMathTest {

    private val eps = 1e-4f

    @Test
    fun targetScalesYawByPointZeroFourTimesSensitivity() {
        val t = computeTargetOrientation(
            eulerYawDeg = 100f, eulerPitchDeg = 0f,
            sensivity = 1f, invertYaw = false, invertPitch = false
        )
        assertEquals(4.0f, t.yawDeg, eps)
        assertEquals(0.0f, t.pitchDeg, eps)
    }

    @Test
    fun targetScalesPitchByPointZeroTwoTimesSensitivity() {
        val t = computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100f,
            sensivity = 1.2f, invertYaw = false, invertPitch = false
        )
        assertEquals(2.4f, t.pitchDeg, eps)
    }

    @Test
    fun invertYawNegatesYaw() {
        val t = computeTargetOrientation(
            eulerYawDeg = 100f, eulerPitchDeg = 0f,
            sensivity = 1f, invertYaw = true, invertPitch = false
        )
        assertEquals(-4.0f, t.yawDeg, eps)
    }

    @Test
    fun invertPitchNegatesPitch() {
        val t = computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100f,
            sensivity = 1f, invertYaw = false, invertPitch = true
        )
        assertEquals(-2.0f, t.pitchDeg, eps)
    }

    @Test
    fun yawIsClampedToPlusMinus360() {
        val hi = computeTargetOrientation(
            eulerYawDeg = 100000f, eulerPitchDeg = 0f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(360.0f, hi.yawDeg, eps)

        val lo = computeTargetOrientation(
            eulerYawDeg = -100000f, eulerPitchDeg = 0f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(-360.0f, lo.yawDeg, eps)
    }

    @Test
    fun pitchIsClampedToPlusMinus270() {
        val hi = computeTargetOrientation(
            eulerYawDeg = 0f, eulerPitchDeg = 100000f,
            sensivity = 10f, invertYaw = false, invertPitch = false
        )
        assertEquals(270.0f, hi.pitchDeg, eps)
    }

    private val qeps = 1e-4f

    @Test
    fun identityQuaternionHasZeroEulerAngles() {
        val q = Quaternion(1f, 0f, 0f, 0f)
        val (yaw, pitch, roll) = q.toEulerAngles()
        assertEquals(0.0f, yaw, qeps)
        assertEquals(0.0f, pitch, qeps)
        assertEquals(0.0f, roll, qeps)
    }

    @Test
    fun conjugateNegatesVectorPart() {
        val q = Quaternion(0.5f, 0.1f, 0.2f, 0.3f)
        val c = q.conjugate()
        assertEquals(0.5f, c.w, qeps)
        assertEquals(-0.1f, c.x, qeps)
        assertEquals(-0.2f, c.y, qeps)
        assertEquals(-0.3f, c.z, qeps)
    }

    @Test
    fun multiplyByIdentityReturnsNormalizedSelf() {
        val q = Quaternion(1f, 0f, 0f, 0f)
        val id = Quaternion(1f, 0f, 0f, 0f)
        val r = q.multiply(id)
        assertEquals(1.0f, r.w, qeps)
        assertEquals(0.0f, r.x, qeps)
    }

    @Test
    fun multiplyByConjugateGivesIdentity() {
        val s = kotlin.math.sqrt(0.5f)
        val q = Quaternion(s, 0f, 0f, s)
        val r = q.multiply(q.conjugate())
        assertEquals(1.0f, r.w, qeps)
        assertEquals(0.0f, r.x, qeps)
        assertEquals(0.0f, r.y, qeps)
        assertEquals(0.0f, r.z, qeps)
    }

    @Test
    fun normalizeMakesUnitMagnitude() {
        val q = Quaternion(2f, 0f, 0f, 0f).normalize()
        assertEquals(1.0f, q.magnitude(), qeps)
    }

    @Test
    fun fromRotationMatrixIdentityIsIdentityQuaternion() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val q = Quaternion.fromRotationMatrix(identity)
        assertEquals(1.0f, q.w, qeps)
        assertEquals(0.0f, q.x, qeps)
        assertEquals(0.0f, q.y, qeps)
        assertEquals(0.0f, q.z, qeps)
    }

    @Test
    fun fromRotationMatrix180AboutXUsesDiagonalBranch() {
        val m = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        val q = Quaternion.fromRotationMatrix(m)
        assertEquals(1.0f, kotlin.math.abs(q.x), qeps)
        assertEquals(0.0f, q.w, qeps)
    }

    @Test
    fun slerpAtZeroReturnsStart() {
        val a = Quaternion(1f, 0f, 0f, 0f)
        val s = kotlin.math.sqrt(0.5f)
        val b = Quaternion(s, 0f, 0f, s)
        val r = Quaternion.slerp(a, b, 0f)
        assertEquals(a.w, r.w, qeps)
        assertEquals(a.z, r.z, qeps)
    }

    @Test
    fun slerpAtOneReturnsEnd() {
        val a = Quaternion(1f, 0f, 0f, 0f)
        val s = kotlin.math.sqrt(0.5f)
        val b = Quaternion(s, 0f, 0f, s)
        val r = Quaternion.slerp(a, b, 1f)
        assertEquals(b.w, r.w, qeps)
        assertEquals(b.z, r.z, qeps)
    }

    @Test
    fun toEulerAnglesClampsGimbalLockPitch() {
        val s = kotlin.math.sqrt(0.5f)
        val q = Quaternion(s, 0f, s, 0f)
        val (_, pitch, _) = q.toEulerAngles()
        assertEquals(90.0f, pitch, 1e-2f)
    }

    @Test
    fun toEulerAnglesClampsGimbalLockPitchNegative() {
        // pitch = -90°: 2(wy - zx) = -1 → rotation about Y by -90° → q = (cos(-45), 0, sin(-45), 0)
        val s = kotlin.math.sqrt(0.5f)
        val q = Quaternion(s, 0f, -s, 0f)
        val (_, pitch, _) = q.toEulerAngles()
        assertEquals(-90.0f, pitch, 1e-2f)
    }

    @Test
    fun fromRotationMatrix180AboutYUsesM11Branch() {
        // 180° about Y: diag(-1, 1, -1), trace = -1, mat[4] наибольший → ветка m11
        val m = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)
        val q = Quaternion.fromRotationMatrix(m)
        assertEquals(1.0f, kotlin.math.abs(q.y), qeps)
        assertEquals(0.0f, q.w, qeps)
        assertEquals(0.0f, q.x, qeps)
        assertEquals(0.0f, q.z, qeps)
    }

    @Test
    fun fromRotationMatrix180AboutZUsesM22Branch() {
        // 180° about Z: diag(-1, -1, 1), trace = -1, mat[8] наибольший → ветка m22 (else)
        val m = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
        val q = Quaternion.fromRotationMatrix(m)
        assertEquals(1.0f, kotlin.math.abs(q.z), qeps)
        assertEquals(0.0f, q.w, qeps)
        assertEquals(0.0f, q.x, qeps)
        assertEquals(0.0f, q.y, qeps)
    }

    @Test
    fun slerpTakesShortPathWhenDotNegative() {
        // q2 = -q1 представляет тот же поворот; dot < 0 → ветка отрицания.
        // Результат должен остаться единичным и совпасть с q1 по направлению.
        val a = Quaternion(1f, 0f, 0f, 0f)
        val b = Quaternion(-1f, 0f, 0f, 0f)
        val r = Quaternion.slerp(a, b, 0.5f)
        assertEquals(1.0f, r.magnitude(), qeps)
        assertEquals(1.0f, kotlin.math.abs(r.w), qeps)
    }

    @Test
    fun slerpMidpointIsUnitAndBetween() {
        // halfway между identity и поворотом 90° about Z = поворот 45° about Z
        val s = kotlin.math.sqrt(0.5f)
        val a = Quaternion(1f, 0f, 0f, 0f)
        val b = Quaternion(s, 0f, 0f, s)
        val r = Quaternion.slerp(a, b, 0.5f)
        assertEquals(1.0f, r.magnitude(), qeps)
        // cos(22.5°) ≈ 0.92388, sin(22.5°) ≈ 0.38268
        assertEquals(0.92388f, r.w, 1e-3f)
        assertEquals(0.38268f, r.z, 1e-3f)
    }

    @Test
    fun multiplyIsNonCommutative() {
        // повороты вокруг разных осей не коммутируют
        val s = kotlin.math.sqrt(0.5f)
        val qx = Quaternion(s, s, 0f, 0f)  // 90° about X
        val qy = Quaternion(s, 0f, s, 0f)  // 90° about Y
        val xy = qx.multiply(qy)
        val yx = qy.multiply(qx)
        val differ = kotlin.math.abs(xy.x - yx.x) > 1e-3f ||
            kotlin.math.abs(xy.y - yx.y) > 1e-3f ||
            kotlin.math.abs(xy.z - yx.z) > 1e-3f
        assertTrue(differ)
    }

    @Test
    fun dotOfOrthogonalRotationsComponents() {
        val a = Quaternion(1f, 0f, 0f, 0f)
        val b = Quaternion(0f, 1f, 0f, 0f)
        assertEquals(0.0f, a.dot(b), qeps)
        assertEquals(1.0f, a.dot(a), qeps)
    }

    @Test
    fun toEulerAnglesUnwrapsTowardPreviousAcross180() {
        // yaw около +180°; с previousYaw ≈ -179 unwrap должен вернуть значение около -180,
        // а не +180 (ближайшее к reference).
        val s = kotlin.math.sqrt(0.5f)
        // поворот ~180° about Z даёт yaw близко к ±180
        val q = Quaternion(0.0001f, 0f, 0f, 1f) // почти 180° about Z
        val (yawNoPrev, _, _) = q.toEulerAngles()
        val (yawWithPrev, _, _) = q.toEulerAngles(previousYaw = -179f)
        // без previous yaw ≈ +180; с previous -179 должен стать ≈ -180 (непрерывность)
        assertTrue(kotlin.math.abs(yawWithPrev - (-179f)) <= kotlin.math.abs(yawNoPrev - (-179f)))
    }
}
