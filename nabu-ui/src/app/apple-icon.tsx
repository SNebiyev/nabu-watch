import {ImageResponse} from "next/og";

export const size = {width: 180, height: 180};
export const contentType = "image/png";

export default function AppleIcon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          background: "linear-gradient(135deg, #0b0f17 0%, #0f1a2b 100%)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <svg width="120" height="120" viewBox="0 0 32 32">
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
      </div>
    ),
    {...size},
  );
}
