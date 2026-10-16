export default function BonfireIcon({ size = 22 }) {
  return (
    <svg
      className="bonfire-icon"
      width={size}
      height={size * 1.25}
      viewBox="0 0 32 40"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path
        className="bonfire-flame"
        d="M8 32C2 27 6 22 7 19c0 5 3 6 3 6-1-6 3-8 4-13 3 4 1 9 5 12 0-3 3-5 3-8 5 6 7 12 2 16"
      />
      <path className="bonfire-blade" d="m14 14 5 1-3 16-3 3-1-4z" />
      <path d="m17 5-2 8m4-8-2 8m-1-5 3 1m-4 2 3 1m-7 0 2 3 8 2 3-2M17 2l3 1 1 2-3 1-2-2z" />
      <path d="m6 33 20 4M6 37l20-5M10 31l12 6M4 38h24" />
      <path className="bonfire-ember" d="m4 29-1-2m24 2 1-3" />
    </svg>
  );
}
