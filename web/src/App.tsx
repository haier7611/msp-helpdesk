import { BrowserRouter } from "react-router";
import { ToastProvider } from "./components/Toast";
import { DisplayNameProvider } from "./DisplayNameProvider";
import { AppRoutes } from "./routes";

export function App() {
  return (
    <DisplayNameProvider>
      <BrowserRouter>
        <ToastProvider>
          <AppRoutes />
        </ToastProvider>
      </BrowserRouter>
    </DisplayNameProvider>
  );
}
