# Read GitHub's workflow-level conclusions. Job names such as "test" are not unique.
require 'base64'
require 'json'
require 'open3'
require 'uri'
require 'yaml'

module EphemeralFastCI
  REQUIRED = %w[unit-tests pr-guard groovy2x-spock].freeze
  FILTERED = %w[groovy24-parse sandbox-lint python-tests ruff self-deploy-recovery
                lane-gate-test lease-scripts-test dependency-updates-check].freeze
  # Fast CI takes about five minutes; the gate polls so no second workflow has to wake it.
  WAIT_SECONDS = 15 * 60
  POLL_SECONDS = 30

  def self.api(path, *options)
    # A transient API read must not strand a green revision.
    3.times do |attempt|
      stdout, stderr, status = Open3.capture3('gh', 'api', *options, "repos/#{ENV.fetch('GITHUB_REPOSITORY')}/#{path}")
      return stdout.empty? ? nil : JSON.parse(stdout) if status.success?
      warn stderr
      transient = stderr.match?(/HTTP (?:429|5\d\d)|timeout|timed out|connection reset|unexpected EOF/i)
      break unless transient && attempt < 2
      sleep 2**(attempt + 1)
    end
    raise "GitHub API request failed for #{path.split('?').first}"
  end

  def self.pages(path)
    api(path, '--paginate', '--slurp')
  end

  def self.workflow_path(name)
    ".github/workflows/#{name}.yml"
  end

  # The fast workflows use literals, *, ** and **/. A new pattern syntax must be
  # supported explicitly, otherwise an applicable path-filtered workflow that has
  # not started yet could be treated as not applicable.
  def self.match_path?(glob, file)
    raise "Unsupported CI path filter: #{glob}" if glob.match?(/[!?+\[\]\\]/)
    parts = glob.scan(%r{\*\*/|\*\*|\*|[^*]+})
    tokens = { '**/' => '(?:.*/)?', '**' => '.*', '*' => '[^/]*' }
    Regexp.new("\\A" + parts.map { |part| tokens.fetch(part) { Regexp.escape(part) } }.join + "\\z").match?(file)
  end

  # The workflow's pull_request path globs (['**'] when unfiltered), or nil without
  # that trigger. Raises on any filter the gate cannot evaluate.
  def self.pull_request_paths(yaml, path)
    config = YAML.safe_load(yaml)
    triggers = config.fetch('on') { config.fetch(true) }
    return unless triggers.key?('pull_request')
    filter = triggers['pull_request'] || {}
    raise "Unsupported pull_request filter in #{path}" unless (filter.keys - %w[paths]).empty?
    filter.fetch('paths', ['**']).each { |glob| match_path?(glob, '') }
  end

  def self.expected(pr, files)
    # Actions evaluates path filters on the first 3,000 changed files, all the files API lists.
    paths = files.flat_map { |f| [f.fetch('filename'), f['previous_filename']].compact }
    FILTERED.select do |name|
      path = workflow_path(name)
      changed = files.find { |f| [f['filename'], f['previous_filename']].include?(path) }
      if changed && (changed['status'] == 'removed' || changed['filename'] != path)
        next false
      end
      # A PR can change which files trigger a lane. Read its YAML as data only.
      yaml = if changed
               Base64.decode64(api("contents/#{path}?ref=#{pr.fetch('head').fetch('sha')}").fetch('content'))
             else
               File.read(path)
             end
      globs = pull_request_paths(yaml, path)
      globs&.any? { |glob| paths.any? { |file| match_path?(glob, file) } }
    end + REQUIRED
  end

  def self.runs(sha, event, head, pr_number = nil)
    query = URI.encode_www_form(head_sha: sha, event: event, per_page: 100)
    rows = pages("actions/runs?#{query}").flat_map { |page| page.fetch('workflow_runs') }
    rows.select! do |run|
      prs = run.fetch('pull_requests')
      run.fetch('head_sha') == sha && run.fetch('event') == event &&
        run['head_branch'] == head.fetch('ref') && run.dig('head_repository', 'full_name') == head.dig('repo', 'full_name') &&
        (!pr_number || prs.empty? || prs.any? { |p| p['number'] == pr_number })
    end
    rows.group_by { |run| run.fetch('path') }.transform_values do |attempts|
      attempts.max_by { |run| [run.fetch('run_number'), run.fetch('run_attempt')] }
    end
  end

  def self.state(expected, runs)
    # Include every fast lane GitHub actually scheduled, even on large diffs.
    names = expected | FILTERED.select { |name| runs.key?(workflow_path(name)) }
    pending = []
    failed = []
    names.each do |name|
      run = runs[workflow_path(name)]
      if !run || run['status'] != 'completed'
        pending << name
      elsif run['conclusion'] != 'success'
        failed << name
      end
    end
    [pending, failed]
  end

  # The PR as it is now (true for main) when head is still current, otherwise nil.
  def self.current(head, pr_number)
    repo = ENV.fetch('GITHUB_REPOSITORY')
    return api('git/ref/heads/main').dig('object', 'sha') == head.fetch('sha') || nil unless pr_number
    pr = api("pulls/#{Integer(pr_number)}")
    pr if pr['state'] == 'open' && pr.dig('base', 'repo', 'full_name') == repo &&
          pr.dig('head', 'sha') == head.fetch('sha') && pr.dig('head', 'repo', 'full_name') == head.dig('repo', 'full_name')
  end

  # ['ready'], ['superseded'], ['failed', names] or ['pending', names].
  def self.check(pr, head)
    number = pr&.fetch('number')
    fresh = current(head, number)
    return ['superseded'] unless fresh
    expected_names = ['unit-tests']
    if pr
      # A re-run gate replays the original event, so read changed_files from the PR as it is now.
      files = pages("pulls/#{Integer(number)}/files?per_page=100").flatten(1)
      # The PR files API lists at most 3000 files.
      raise 'Incomplete PR file list' unless files.size == [fresh.fetch('changed_files'), 3000].min
      expected_names = expected(fresh, files)
    end
    pending, failed = state(expected_names, runs(head.fetch('sha'), pr ? 'pull_request' : 'push', head, number))
    return ['failed', failed] unless failed.empty?
    return ['pending', pending] unless pending.empty?
    [current(head, number) ? 'ready' : 'superseded']
  end

  def self.report(detail)
    puts "::notice title=Ephemeral hubs not started::#{detail}"
    summary = ENV['GITHUB_STEP_SUMMARY']
    File.open(summary, 'a') { |out| out.puts "Ephemeral hubs not started: #{detail}" } if summary
  end

  def self.decision(pr, head)
    clock = -> { Process.clock_gettime(Process::CLOCK_MONOTONIC) }
    deadline = clock.call + Integer(ENV.fetch('FAST_CI_WAIT_SECONDS', WAIT_SECONDS))
    loop do
      result, names = check(pr, head)
      return result unless names
      waiting = result == 'pending' && clock.call < deadline
      unless waiting
        report(result == 'failed' ? "fast CI did not pass: #{names.join(', ')}" : "still waiting for #{names.join(', ')}")
        return 'deferred'
      end
      puts "Waiting for #{names.join(', ')}"
      sleep Integer(ENV.fetch('FAST_CI_POLL_SECONDS', POLL_SECONDS))
    end
  end
end

if __FILE__ == $PROGRAM_NAME
  payload = JSON.parse(File.read(ENV.fetch('GITHUB_EVENT_PATH')))
  pr = payload['pull_request']
  head = pr ? pr.fetch('head') : { 'ref' => 'main', 'sha' => ENV.fetch('GITHUB_SHA'), 'repo' => payload.fetch('repository') }
  result = EphemeralFastCI.decision(pr, head)
  File.open(ENV.fetch('GITHUB_OUTPUT'), 'a') { |out| out.puts "result=#{result}" }
end
