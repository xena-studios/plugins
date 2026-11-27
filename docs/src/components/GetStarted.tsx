// From a jar to a running plugin, on the home page: three hairline rows, each linking the page
// that says more. The same for every plugin (start/installing).
import Link from "fumadocs-core/link";

const steps = [
  { title: "Download it.", text: "Every plugin's download page has the latest jar, and every version before it.", href: "/start/requirements/", link: "Requirements" },
  { title: "Drop it in.", text: "Put the jar in your server's plugins/ folder and restart. It writes its config on the first start.", href: "/start/installing/", link: "Installing" },
  { title: "Make it yours.", text: "Edit the config and run /<plugin> reload. Settings apply without a restart, but for a few storage ones.", href: "/start/permissions/", link: "Permissions" },
];

export default function GetStarted() {
  return (
    <section className="section not-prose" aria-labelledby="get-started-title">
      <div className="wrap">
        <h2 id="get-started-title">Running in three steps</h2>
        <ol className="step-list" role="list">
          {steps.map((s, i) => (
            <li key={s.title} className="step">
              <span className="label">0{i + 1}</span>
              <h3>{s.title}</h3>
              <p>{s.text}</p>
              <Link className="more" href={s.href}>
                {s.link}
              </Link>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
