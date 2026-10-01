/** Shown while a server page waits on the API, shaped like the pages it stands in for. */
export default function Loading() {
  return (
    <div aria-busy="true" aria-label="Đang tải dữ liệu" className="space-y-6">
      <div className="space-y-3">
        <div className="skeleton h-3 w-40" />
        <div className="skeleton h-8 w-80 max-w-full" />
        <div className="skeleton h-4 w-[32rem] max-w-full" />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <div className="card card-pad lg:col-span-2">
          <div className="skeleton h-3 w-32" />
          <div className="skeleton mt-4 h-12 w-64" />
          <div className="skeleton mt-6 h-32 w-full" />
        </div>
        <div className="grid gap-4">
          <div className="card card-pad">
            <div className="skeleton h-3 w-24" />
            <div className="skeleton mt-3 h-8 w-40" />
          </div>
          <div className="card card-pad">
            <div className="skeleton h-3 w-24" />
            <div className="skeleton mt-3 h-8 w-40" />
          </div>
        </div>
      </div>
      <div className="card card-pad">
        <div className="skeleton h-4 w-48" />
        <div className="mt-5 space-y-3">
          {[0, 1, 2].map((row) => (
            <div key={row} className="skeleton h-10 w-full" />
          ))}
        </div>
      </div>
    </div>
  );
}
