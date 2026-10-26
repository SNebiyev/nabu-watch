import {ImageResponse} from "next/og";

export const alt = "nabu-watch — Website Security & Compliance Monitor";
export const size = {width: 1200, height: 630};
export const contentType = "image/png";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          background:
            "radial-gradient(circle at 20% 30%, #0f1a2b 0%, #0b0f17 60%, #06090f 100%)",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          padding: "80px 96px",
          color: "#f8fafc",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{display: "flex", alignItems: "center", gap: 24, marginBottom: 48}}>
          <svg width="84" height="84" viewBox="0 0 32 32">
            <path
              d="M16 3 L27 7 V16 C27 22 22 27 16 29 C10 27 5 22 5 16 V7 Z"
              fill="none"
              stroke="#38bdf8"
              strokeWidth={2}
              strokeLinejoin="round"
            />
            <circle cx={16} cy={16} r={3} fill="#38bdf8" />
            <circle
              cx={16}
              cy={16}
              r={6.5}
              fill="none"
              stroke="#38bdf8"
              strokeWidth={1.5}
              opacity={0.55}
            />
          </svg>
          <div style={{fontSize: 44, fontWeight: 700, letterSpacing: -1}}>nabu-watch</div>
        </div>
        <div
          style={{
            fontSize: 68,
            fontWeight: 700,
            lineHeight: 1.1,
            letterSpacing: -2,
            maxWidth: 980,
          }}
        >
          Monitor SSL + GDPR compliance for every website you own.
        </div>
        <div
          style={{
            marginTop: 36,
            fontSize: 28,
            color: "#94a3b8",
            maxWidth: 880,
          }}
        >
          Continuous scans across regions. Instant alerts. Free to start.
        </div>
      </div>
    ),
    {...size},
  );
}
