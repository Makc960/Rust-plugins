using System;

namespace UnityEngine
{
    public class Object { public string name; public static implicit operator bool(Object o) { return !ReferenceEquals(o, null); } }
    public class Component : Object { public Transform transform; public GameObject gameObject; }
    public class Behaviour : Component { }
    public class MonoBehaviour : Behaviour { }
    public class Transform : Component { public Vector3 position; }
    public class GameObject : Object { }

    public struct Vector3
    {
        public float x, y, z;
        public Vector3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
        public static Vector3 up = new Vector3(0f, 1f, 0f);
        public static Vector3 zero = new Vector3(0f, 0f, 0f);
        public float magnitude { get { return Mathf.Sqrt(x * x + y * y + z * z); } }
        public static float Distance(Vector3 a, Vector3 b) { return 0f; }
        public static Vector3 operator +(Vector3 a, Vector3 b) { return new Vector3(a.x + b.x, a.y + b.y, a.z + b.z); }
        public static Vector3 operator -(Vector3 a, Vector3 b) { return new Vector3(a.x - b.x, a.y - b.y, a.z - b.z); }
        public static Vector3 operator *(Vector3 a, float d) { return new Vector3(a.x * d, a.y * d, a.z * d); }
    }

    public static class Mathf
    {
        public static float Clamp(float v, float min, float max) { return v < min ? min : (v > max ? max : v); }
        public static int Clamp(int v, int min, int max) { return v < min ? min : (v > max ? max : v); }
        public static float Clamp01(float v) { return Clamp(v, 0f, 1f); }
        public static int Max(int a, int b) { return a > b ? a : b; }
        public static float Max(float a, float b) { return a > b ? a : b; }
        public static int Min(int a, int b) { return a < b ? a : b; }
        public static float Min(float a, float b) { return a < b ? a : b; }
        public static int FloorToInt(float f) { return (int)Math.Floor(f); }
        public static int CeilToInt(float f) { return (int)Math.Ceiling(f); }
        public static int RoundToInt(float f) { return (int)Math.Round(f); }
        public static float Floor(float f) { return (float)Math.Floor(f); }
        public static float Ceil(float f) { return (float)Math.Ceiling(f); }
        public static float Sqrt(float f) { return (float)Math.Sqrt(f); }
        public static float Lerp(float a, float b, float t) { return a + (b - a) * t; }
        public static float InverseLerp(float a, float b, float v) { return 0f; }
    }

    public enum TextAnchor
    {
        UpperLeft, UpperCenter, UpperRight,
        MiddleLeft, MiddleCenter, MiddleRight,
        LowerLeft, LowerCenter, LowerRight
    }

    public static class Time { public static float realtimeSinceStartup; }
    public static class Random { public static float value; public static float Range(float a, float b) { return a; } }
}

namespace UnityEngine
{
    public struct Color
    {
        public float r, g, b, a;
        public Color(float r, float g, float b, float a) { this.r = r; this.g = g; this.b = b; this.a = a; }
    }

    public static class ColorUtility
    {
        public static bool TryParseHtmlString(string html, out Color color) { color = new Color(1f, 1f, 1f, 1f); return true; }
    }

    namespace UI
    {
        public class ScrollRect
        {
            public enum MovementType { Unrestricted, Elastic, Clamped }
        }
    }
}

public class TOD_Cycle { public float Hour; }
public class TOD_Sky
{
    public static TOD_Sky Instance;
    public TOD_Cycle Cycle = new TOD_Cycle();
}
