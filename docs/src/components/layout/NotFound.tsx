// The not-found page: in the studio's frame, kept out of search engines (src/routes/404.tsx).
import Link from "fumadocs-core/link";
import { XenaShell } from "./XenaShell.tsx";

export function NotFound() {
  return (
    <XenaShell>
      <section className="section not-found">
        <div className="wrap">
          <p className="kicker">// 404</p>
          <h1>Page not found</h1>
          <p className="lead">This page doesn't exist, or it moved. Start again from the home page, or search the docs from there.</p>
          <p className="actions">
            <Link className="gui-button primary" href="/">
              Back to the docs
            </Link>
          </p>
        </div>
      </section>
    </XenaShell>
  );
}
