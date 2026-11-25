import { Link } from "react-router";
import { Card } from "../components/ui";
import { PlainLayout } from "../layouts/Layouts";

export function NotFoundPage() {
  return (
    <PlainLayout>
      <Card>
        <h1>Page not found</h1>
        <p>This address does not match any page.</p>
        <p>
          <Link to="/">Go to the start page</Link>
        </p>
      </Card>
    </PlainLayout>
  );
}
