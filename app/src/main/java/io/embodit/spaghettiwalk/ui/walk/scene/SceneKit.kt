package io.embodit.spaghettiwalk.ui.walk.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.PlateUv
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.theme.WalkColors
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.P2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (same approach as the Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f)
    else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity {
    runCatching { setName(name) }
    return this
}

/** A box collider of [size] (local metres) that the system pointer can tap. */
internal fun Entity.tapBox(size: Vec3, offset: Vec3 = Vec3.ZERO) {
    val shape = ShapeResource.createBox(size.sdk()).let { if (offset == Vec3.ZERO) it else it.offsetByTranslation(offset.sdk()) }
    components.set(CollisionComponent(collisionShape = listOf(shape), physicsMaterial = PhysicsMaterialResource()))
    components.set(InteractableComponent())
}

/**
 * Resources shared by entities that come and go (materials, meshes). The SDK releases a resource when
 * its last user entity is destroyed; shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** Scene-lifetime materials, one per appearance. All unlit, tone mapping off so the design colours are exact. */
internal class SceneMaterials(private val shared: SharedResources) {
    val white: Material = unlit(WalkColors.Pole, twoSided = true)
    val interaction: Material = unlit(WalkColors.Interaction, twoSided = true)
    val alert: Material = unlit(WalkColors.Alert, twoSided = true)

    /** Station outlines: type colour at 30 % fill, solid edge (design `entities3d.station`). */
    private val fills = HashMap<StationType, Material>()
    private val edges = HashMap<StationType, Material>()

    fun fill(t: StationType): Material = fills.getOrPut(t) { sheer(WalkColors.station(t), 0.30f) }

    fun edge(t: StationType): Material = edges.getOrPut(t) { unlit(WalkColors.station(t), twoSided = true) }

    /** The placement ghost: type colour at 40 %. */
    private val ghosts = HashMap<StationType, Material>()

    fun ghost(t: StationType): Material = ghosts.getOrPut(t) { sheer(WalkColors.station(t), 0.40f) }

    /** Ribbon segments: ramp colour (quantised to 48 steps) at an opacity. */
    private val ribbons = HashMap<Int, Material>()

    fun ribbon(shade: Float, opacity: Float): Material {
        val q = (shade.coerceIn(0f, 1f) * 47f).toInt()
        val o = (opacity * 20f).toInt()
        return ribbons.getOrPut(q * 100 + o) { sheer(WalkColors.ramp(q / 47f), o / 20f) }
    }

    /** The old path while rearranging, and the Before path under the After one: grey at 35 %. */
    val ribbonDim: Material = sheer(WalkColors.PathDim, 0.35f)
    val afterLine: Material = sheer(WalkColors.AfterLine, 0.80f)

    /** Live trail dots: white, older ones fainter (30 % down to 9 %). */
    val trail: List<Material> = (0 until 4).map { sheer(WalkColors.AfterLine, 0.30f - it * 0.07f) }

    val stopDisc: Material = sheer(WalkColors.StopPoint, 0.40f)
    val stopDiscSelected: Material = sheer(WalkColors.Interaction, 0.50f)
    val floorLine: Material = sheer(WalkColors.FloorGrid, 0.12f)
    val floorEdge: Material = sheer(WalkColors.FloorGrid, 0.40f)

    /** Snap frames: bench ends interaction yellow 70 %, the original spot grey; solid while snapped. */
    val frameBench: Material = sheer(WalkColors.Interaction, 0.70f)
    val frameHome: Material = sheer(WalkColors.PathDim, 0.80f)
    val frameSolid: Material = unlit(WalkColors.Interaction, twoSided = true)

    private fun unlit(color: Color, twoSided: Boolean = false, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
                if (!depthTest) {
                    setDepthTest(false)
                    setDepthWrite(false)
                }
            }
        )

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    fun sheer(color: Color, opacity: Float, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )

    /** A textured, two-sided face (plates). [transparent]: the bitmap's alpha cuts its shape. */
    fun textured(bitmap: Bitmap, fallback: Color, transparent: Boolean = false): Material =
        try {
            shared.keep(
                UnlitMaterial.create(if (transparent) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setBaseColorTexture(TextureResource.create(bitmap))
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                    if (transparent) setDepthWrite(false)
                }
            )
        } catch (t: Throwable) {
            WalkLog.e("SCENE texture failed: $t", t)
            unlit(fallback, twoSided = true)
        }
}

/** Programmatic meshes (`MeshModel`: positions, indices, normals, optional uv). */
internal object MeshFactory {
    private val UP = Vector3(0f, 1f, 0f)
    private val FRONT = Vector3(0f, 0f, 1f)

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, 0.001f, radius)),
            name = name,
        )
    }

    /**
     * Disc in the local XY plane facing +Z with texture coordinates that map the unit square onto it:
     * the icon plates (the texture's circle fills the disc exactly, no transparency needed). UVs from
     * [PlateUv.uv] (v = 1 on top: the engine samples v = 0 from the bitmap's bottom row; v0.1.2 fix).
     */
    fun discUv(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val uvs = ArrayList<Vector2>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += FRONT
        uvs += Vector2(0.5f, 0.5f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            val x = cos(a).toFloat()
            val y = sin(a).toFloat()
            positions += Vector3(x * radius, y * radius, 0f)
            normals += FRONT
            val (u, v) = PlateUv.uv(x, y)
            uvs += Vector2(u, v)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, radius, 0.001f)),
            name = name,
        )
    }

    /** Flat ring in the local XZ plane, facing +Y; with [dashes] > 0 only that many arcs are drawn. */
    fun ring(inner: Float, outer: Float, name: String, dashes: Int = 0, dashFill: Float = 0.6f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun arc(a0: Double, a1: Double, steps: Int) {
            for (i in 0 until steps) {
                val s = a0 + (a1 - a0) * i / steps
                val e = a0 + (a1 - a0) * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += UP }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        if (dashes <= 0) {
            arc(0.0, 2 * PI, segments)
        } else {
            val slot = 2 * PI / dashes
            for (d in 0 until dashes) arc(d * slot, d * slot + slot * dashFill, 3)
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer)),
            name = name,
        )
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width (needle, leaders). */
    fun unitBand(name: String): MeshResource {
        val positions = listOf(Vector3(0f, -0.5f, 0f), Vector3(1f, -0.5f, 0f), Vector3(1f, 0.5f, 0f), Vector3(0f, 0.5f, 0f))
        val normals = List(4) { FRONT }
        val indices = listOf(0, 1, 2, 0, 2, 3)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )
    }

    /** Closed cylinder of [radius] and [height] along +Y from y = 0. */
    fun cylinder(radius: Float, height: Float, name: String, segments: Int = 20): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        // side
        for (i in 0 until segments) {
            val a0 = 2 * PI * i / segments
            val a1 = 2 * PI * (i + 1) / segments
            val n0 = Vector3(cos(a0).toFloat(), 0f, sin(a0).toFloat())
            val n1 = Vector3(cos(a1).toFloat(), 0f, sin(a1).toFloat())
            val base = positions.size
            positions += Vector3(n0.x * radius, 0f, n0.z * radius)
            positions += Vector3(n1.x * radius, 0f, n1.z * radius)
            positions += Vector3(n1.x * radius, height, n1.z * radius)
            positions += Vector3(n0.x * radius, height, n0.z * radius)
            normals += n0
            normals += n1
            normals += n1
            normals += n0
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        // caps
        fun cap(y: Float, up: Boolean) {
            val center = positions.size
            positions += Vector3(0f, y, 0f)
            normals += if (up) UP else Vector3(0f, -1f, 0f)
            for (i in 0 until segments) {
                val a = 2 * PI * i / segments
                positions += Vector3((cos(a) * radius).toFloat(), y, (sin(a) * radius).toFloat())
                normals += if (up) UP else Vector3(0f, -1f, 0f)
            }
            for (i in 0 until segments) {
                val a = center + 1 + i
                val b = center + 1 + (i + 1) % segments
                if (up) {
                    indices += center
                    indices += b
                    indices += a
                } else {
                    indices += center
                    indices += a
                    indices += b
                }
            }
        }
        cap(height, true)
        cap(0f, false)
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, height / 2f, 0f), halfExtent = Vector3(radius, height / 2f, radius)),
            name = name,
        )
    }

    /** Cone with its apex at the origin, its axis along +Z, length 1 and base radius 1 (scaled to use). */
    fun cone(name: String, segments: Int = 16): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        positions += Vector3(0f, 0f, 0f)
        normals += Vector3(0f, 0f, -1f)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3(cos(a).toFloat(), sin(a).toFloat(), 1f)
            normals += Vector3(cos(a).toFloat(), sin(a).toFloat(), 0f)
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % segments
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0.5f), halfExtent = Vector3(1f, 1f, 0.5f)),
            name = name,
        )
    }

    /**
     * Teardrop body of revolution: height 1 along +Y from y = 0, widest radius 1 near the lower third,
     * pointed at the top (design `entities3d.flame`: at most 160 triangles per layer).
     */
    fun teardrop(name: String, around: Int = 8, rings: Int = 9): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun radiusAt(t: Float): Float {
            // bulb low, tip high; normalised so that the maximum is 1
            val r = sin(PI * t.toDouble().pow(0.8)).toFloat().coerceAtLeast(0f).pow(0.9f) * (1f - t).pow(0.35f)
            return r / 0.86f
        }
        // bottom centre
        positions += Vector3(0f, 0f, 0f)
        normals += Vector3(0f, -1f, 0f)
        for (j in 1 until rings) {
            val t = j.toFloat() / rings
            val r = radiusAt(t)
            for (i in 0 until around) {
                val a = 2 * PI * i / around
                val x = cos(a).toFloat()
                val z = sin(a).toFloat()
                positions += Vector3(x * r, t, z * r)
                normals += Vector3(x, 0.3f, z)
            }
        }
        // tip
        positions += Vector3(0f, 1f, 0f)
        normals += UP
        val tip = positions.size - 1
        // bottom fan
        for (i in 0 until around) {
            indices += 0
            indices += 1 + i
            indices += 1 + (i + 1) % around
        }
        // bands
        for (j in 0 until rings - 2) {
            val a0 = 1 + j * around
            val a1 = 1 + (j + 1) * around
            for (i in 0 until around) {
                val i1 = (i + 1) % around
                indices += a0 + i
                indices += a1 + i
                indices += a0 + i1
                indices += a0 + i1
                indices += a1 + i
                indices += a1 + i1
            }
        }
        // tip fan
        val last = 1 + (rings - 2) * around
        for (i in 0 until around) {
            indices += last + i
            indices += tip
            indices += last + (i + 1) % around
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0.5f, 0f), halfExtent = Vector3(1f, 0.5f, 1f)),
            name = name,
        )
    }

    /** Flat quad strip in the local XZ plane from [edges] (left / right pairs, as `Ribbon.band` gives them), facing +Y. */
    fun band(edges: List<P2>, name: String): MeshResource? {
        if (edges.size < 4) return null
        val positions = ArrayList<Vector3>(edges.size)
        val normals = ArrayList<Vector3>(edges.size)
        val indices = ArrayList<Int>((edges.size / 2 - 1) * 6)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (p in edges) {
            positions += Vector3(p.x, 0f, p.z)
            normals += UP
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minZ = minOf(minZ, p.z)
            maxZ = maxOf(maxZ, p.z)
        }
        for (i in 0 until edges.size / 2 - 1) {
            val a = i * 2
            indices += a
            indices += a + 1
            indices += a + 2
            indices += a + 1
            indices += a + 3
            indices += a + 2
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3((minX + maxX) / 2f, 0f, (minZ + maxZ) / 2f), halfExtent = Vector3((maxX - minX) / 2f + 0.01f, 0.001f, (maxZ - minZ) / 2f + 0.01f)),
            name = name,
        )
    }

    /** Rounded rectangle [w] x [d] (corner [r]) in the local XZ plane facing +Y, centred, filled. */
    fun roundRect(w: Float, d: Float, r: Float, name: String, steps: Int = 6): MeshResource {
        val outline = roundRectOutline(w, d, r, steps)
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (p in outline) {
            positions += Vector3(p.x, 0f, p.z)
            normals += UP
        }
        for (i in outline.indices) {
            indices += 0
            indices += 1 + (i + 1) % outline.size
            indices += 1 + i
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(w / 2f, 0.001f, d / 2f)),
            name = name,
        )
    }

    /** The edge of a rounded rectangle as a band of [width] inside the outline; with [dashes] > 0 only dashes. */
    fun roundRectEdge(w: Float, d: Float, r: Float, width: Float, name: String, dashes: Int = 0, steps: Int = 6): MeshResource {
        val outer = roundRectOutline(w, d, r, steps)
        val inner = roundRectOutline((w - 2 * width).coerceAtLeast(0.01f), (d - 2 * width).coerceAtLeast(0.01f), (r - width).coerceAtLeast(0.001f), steps)
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val n = outer.size
        for (i in 0 until n) {
            if (dashes > 0 && (i * dashes * 2 / n) % 2 == 1) continue
            val j = (i + 1) % n
            val base = positions.size
            positions += Vector3(outer[i].x, 0f, outer[i].z)
            positions += Vector3(outer[j].x, 0f, outer[j].z)
            positions += Vector3(inner[j].x, 0f, inner[j].z)
            positions += Vector3(inner[i].x, 0f, inner[i].z)
            repeat(4) { normals += UP }
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(w / 2f, 0.001f, d / 2f)),
            name = name,
        )
    }

    /** Outline points of a rounded rectangle, counter-clockwise, 4 x ([steps] + 1) points. */
    private fun roundRectOutline(w: Float, d: Float, r0: Float, steps: Int): List<P2> {
        val r = r0.coerceAtMost(minOf(w, d) / 2f)
        val hx = w / 2f - r
        val hz = d / 2f - r
        val out = ArrayList<P2>()
        val corners = listOf(P2(hx, hz) to 0.0, P2(-hx, hz) to PI / 2, P2(-hx, -hz) to PI, P2(hx, -hz) to 3 * PI / 2)
        for ((c, a0) in corners) {
            for (i in 0..steps) {
                val a = a0 + (PI / 2) * i / steps
                out += P2(c.x + (cos(a) * r).toFloat(), c.z + (sin(a) * r).toFloat())
            }
        }
        return out
    }

    /** A small triangle in the XZ plane pointing +Z (the station's front mark), [size] long. */
    fun frontMark(size: Float, name: String): MeshResource {
        val positions = listOf(Vector3(0f, 0f, size * 0.6f), Vector3(-size * 0.5f, 0f, -size * 0.4f), Vector3(size * 0.5f, 0f, -size * 0.4f))
        val normals = List(3) { UP }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = listOf(0, 2, 1), normals = normals),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(size, 0.001f, size)),
            name = name,
        )
    }

    /** Textured quad in the local XY plane facing +Z, [w] x [h]; v = 1 on top (the engine samples v = 0 from the bitmap's bottom row). */
    fun quadUv(w: Float, h: Float, name: String): MeshResource {
        val positions = listOf(Vector3(-w / 2f, -h / 2f, 0f), Vector3(w / 2f, -h / 2f, 0f), Vector3(w / 2f, h / 2f, 0f), Vector3(-w / 2f, h / 2f, 0f))
        val uvs = listOf(Vector2(0f, 0f), Vector2(1f, 0f), Vector2(1f, 1f), Vector2(0f, 1f))
        val normals = List(4) { FRONT }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = listOf(0, 1, 2, 0, 2, 3), normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(w / 2f, h / 2f, 0.001f)),
            name = name,
        )
    }

    /** A box mesh (corner radius 0). */
    fun box(size: Vec3, corner: Float = 0f): MeshResource = MeshResource.createBox(size.sdk(), corner)
}

/** A straight strip between two Stage points that turns its face to [faceNormal] (needle, leaders). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material)
    private var on = true

    init {
        entity.named(name)
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun setMaterial(value: Material) = entity.setMaterial(value)

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** Time-based easing helpers. */
internal object Ease {
    fun inOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    fun outCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }
}
