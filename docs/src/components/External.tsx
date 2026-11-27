// The outbound arrow from xenastudios.co (its only icon): authored SVG in currentColor, nudging
// up-right on hover and focus (styles/theme.css). Every link to another site carries it.
export function External() {
  return (
    <svg className="external" viewBox="0 0 10 10" width="10" height="10" aria-hidden="true" focusable="false">
      <path d="M2.5 7.5 7.5 2.5M3.5 2.5h4v4" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
export default External;
