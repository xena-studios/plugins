// The components every page can use without importing them: Fumadocs' (code blocks, tables,
// internal links), Starlight's that Markdown turns into (Aside, from :::note), and headings that
// link to themselves, as Starlight's did.
import defaultMdxComponents from "fumadocs-ui/mdx";
import type { MDXComponents } from "mdx/types";
import type { ComponentProps, ElementType } from "react";
import * as starlight from "./starlight.tsx";

function heading(Tag: ElementType) {
  return function Heading({ id, children, ...props }: ComponentProps<"h2">) {
    return (
      <Tag id={id} className="heading" {...props}>
        {id ? (
          <a href={`#${id}`} className="heading-anchor">
            {children}
          </a>
        ) : (
          children
        )}
      </Tag>
    );
  };
}

const headings = { h2: heading("h2"), h3: heading("h3"), h4: heading("h4"), h5: heading("h5"), h6: heading("h6") };

export function getMDXComponents(components?: MDXComponents): MDXComponents {
  return { ...defaultMdxComponents, ...headings, ...starlight, ...components };
}
